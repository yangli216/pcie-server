package com.regionalai.floatingball.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import com.regionalai.floatingball.server.common.web.ClientIpUtils;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyView;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.ReadListener;
import javax.servlet.ServletInputStream;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletRequestWrapper;
import javax.servlet.http.HttpServletResponse;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;

@Component
public class DeviceAuthFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(DeviceAuthFilter.class);
    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final RequestSignatureVerifier signatureVerifier;
    private final SecurityRejectionLogService rejectionLogService;
    private final ObjectMapper objectMapper;

    public DeviceAuthFilter(DeviceService deviceService,
                            ReleaseService releaseService,
                            RequestSignatureVerifier signatureVerifier,
                            SecurityRejectionLogService rejectionLogService,
                            ObjectMapper objectMapper) {
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.signatureVerifier = signatureVerifier;
        this.rejectionLogService = rejectionLogService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return !uri.startsWith("/v1/")
            || "/v1/client/register".equals(uri)
            || "/v1/ai/speech/realtime/ws".equals(uri)
            || uri.startsWith("/v1/client/releases/")
            || uri.startsWith("/admin/")
            || "OPTIONS".equalsIgnoreCase(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        CachedBodyHttpServletRequest wrappedRequest = new CachedBodyHttpServletRequest(request);
        String authHeader = wrappedRequest.getHeader("Authorization");
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            log.warn("device auth failed: missing or invalid authorization header. uri={}", wrappedRequest.getRequestURI());
            recordRejection("AUTH_MISSING_TOKEN", wrappedRequest, null, "缺少设备令牌", "Authorization header missing or malformed", false, null, null);
            writeUnauthorized(response, wrappedRequest, "缺少设备令牌");
            return;
        }

        String token = authHeader.substring("Bearer ".length()).trim();
        AiDevice device = deviceService.findActiveByToken(token);
        if (device == null) {
            log.warn("device auth failed: invalid or inactive token. uri={}", wrappedRequest.getRequestURI());
            recordRejection("AUTH_INVALID_TOKEN", wrappedRequest, null, "设备令牌无效或已停用", "Token does not match any active device", false, null, null);
            writeUnauthorized(response, wrappedRequest, "设备令牌无效或已停用");
            return;
        }

        String updateChannel = wrappedRequest.getHeader("X-Update-Channel");
        String clientVersion = wrappedRequest.getHeader("X-Client-Version");
        if (!StringUtils.hasText(clientVersion)) {
            clientVersion = device.getClientVersion();
        }
        if (releaseService.isUpdateRequired(updateChannel, clientVersion)) {
            ReleasePolicyView policy = releaseService.getRequiredPolicy(updateChannel);
            log.warn("device version too old, update required. uri={}, clientVersion={}, minSupportedVersion={}",
                wrappedRequest.getRequestURI(), clientVersion, policy.getMinSupportedVersion());
            recordRejection("VERSION_OUTDATED", wrappedRequest, device, "客户端版本过低", "minSupported=" + policy.getMinSupportedVersion() + " current=" + clientVersion, false, null, null);
            writeUpdateRequired(response, wrappedRequest, policy.getMinSupportedVersion());
            return;
        }

        if (StringUtils.hasText(device.getDevicePublicKey())) {
            String timestamp = wrappedRequest.getHeader("X-Timestamp");
            String nonce = wrappedRequest.getHeader("X-Nonce");
            String signature = wrappedRequest.getHeader("X-Signature");
            String declaredBodySha256 = wrappedRequest.getHeader("X-Body-SHA256");

            boolean hasSigHeaders = timestamp != null && nonce != null && signature != null;

            if (!hasSigHeaders) {
                log.warn("device signature missing. uri={}", wrappedRequest.getRequestURI());
                recordRejection("SIG_MISSING", wrappedRequest, device, "缺少请求签名", "Expected X-Timestamp/X-Nonce/X-Signature headers", false, timestamp, nonce);
                writeSignatureRejected(response, wrappedRequest, "缺少请求签名");
                return;
            }

            String method = wrappedRequest.getMethod().toUpperCase();
            String path = wrappedRequest.getRequestURI();
            String bodyHash = sha256Hex(wrappedRequest.getCachedBody());
            if (StringUtils.hasText(declaredBodySha256) && !declaredBodySha256.equalsIgnoreCase(bodyHash)) {
                log.warn("device body hash mismatch. uri={}, deviceId={}", wrappedRequest.getRequestURI(), device.getIdDevice());
                recordRejection("SIG_BODY_HASH_MISMATCH", wrappedRequest, device, "请求体摘要不匹配", "X-Body-SHA256 does not match actual request body", true, timestamp, nonce);
                writeSignatureRejected(response, wrappedRequest, "请求体摘要不匹配");
                return;
            }

            RequestSignatureVerifier.VerificationResult result = signatureVerifier.verify(
                device.getIdDevice(), device.getDevicePublicKey(), method, path, timestamp, nonce, bodyHash, signature);

            if (result.isStoreUnavailable()) {
                log.error("device signature nonce store unavailable. uri={}, deviceId={}",
                    wrappedRequest.getRequestURI(), device.getIdDevice());
                recordRejection("NONCE_STORE_UNAVAILABLE", wrappedRequest, device,
                    "请求安全校验服务不可用", result.getErrorMessage(), true, timestamp, nonce);
                writeSecurityUnavailable(response, wrappedRequest);
                return;
            }

            if (!result.isValid()) {
                log.warn("device signature invalid. uri={}, deviceId={}, reason={}", wrappedRequest.getRequestURI(), device.getIdDevice(), result.getErrorMessage());
                recordRejection("SIG_INVALID", wrappedRequest, device, "签名验证失败", result.getErrorMessage(), true, timestamp, nonce);
                writeSignatureRejected(response, wrappedRequest, result.getErrorMessage());
                return;
            }
        } else {
            log.warn("device has no public key registered. uri={}, deviceId={}", wrappedRequest.getRequestURI(), device.getIdDevice());
            recordRejection("SIG_NO_PUBLIC_KEY", wrappedRequest, device, "设备未注册公钥", "Device registered without ECDSA public key", false, null, null);
            writeUnauthorized(response, wrappedRequest, "设备未注册公钥，请重新注册");
            return;
        }

        try {
            DeviceContextHolder.set(device);
            filterChain.doFilter(wrappedRequest, response);
        } finally {
            DeviceContextHolder.clear();
        }
    }

    private String sha256Hex(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(data == null ? new byte[0] : data);
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 digest failed", ex);
        }
    }

    private static class CachedBodyHttpServletRequest extends HttpServletRequestWrapper {

        private final byte[] cachedBody;

        CachedBodyHttpServletRequest(HttpServletRequest request) throws IOException {
            super(request);
            this.cachedBody = StreamUtils.copyToByteArray(request.getInputStream());
        }

        byte[] getCachedBody() {
            return cachedBody;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream inputStream = new ByteArrayInputStream(cachedBody);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return inputStream.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener readListener) {
                    if (readListener == null) {
                        return;
                    }
                    try {
                        readListener.onDataAvailable();
                        if (isFinished()) {
                            readListener.onAllDataRead();
                        }
                    } catch (IOException ex) {
                        readListener.onError(ex);
                    }
                }

                @Override
                public int read() {
                    return inputStream.read();
                }
            };
        }

        @Override
        public BufferedReader getReader() {
            return new BufferedReader(new InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }

    private void recordRejection(String type, HttpServletRequest request, AiDevice device,
                                  String reason, String detail, boolean hasSignature,
                                  String timestamp, String nonce) {
        String clientIp = ClientIpUtils.resolve(request);
        SecurityRejectionLogService.RejectionRecord record = SecurityRejectionLogService.RejectionRecord
            .of(type, request.getMethod(), request.getRequestURI(), clientIp)
            .device(device)
            .requestId(request.getHeader("X-Request-Id"))
            .reason(reason)
            .detail(detail)
            .signature(hasSignature, timestamp, nonce)
            .clientInfo(request.getHeader("X-Client-Version"), request.getHeader("X-Update-Channel"));

        rejectionLogService.logRejection(record);
    }

    private void writeUnauthorized(HttpServletResponse response,
                                   HttpServletRequest request,
                                   String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String requestId = request.getHeader("X-Request-Id");
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error("AUTH-401", message, requestId == null ? "N/A" : requestId)
        ));
    }

    private void writeSignatureRejected(HttpServletResponse response,
                                         HttpServletRequest request,
                                         String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error(
                "SIG-401",
                "请求签名验证失败，请重新连接后台；如仍失败，请联系管理员重新注册设备",
                RequestIdUtils.resolve(request)
            )
        ));
    }

    private void writeSecurityUnavailable(HttpServletResponse response,
                                          HttpServletRequest request) throws IOException {
        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", "1");
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error(
                "SECURITY-503",
                "请求安全校验服务暂时不可用，请稍后重试",
                RequestIdUtils.resolve(request)
            )
        ));
    }

    private void writeUpdateRequired(HttpServletResponse response,
                                     HttpServletRequest request,
                                     String minSupportedVersion) throws IOException {
        response.setStatus(426);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        String requestId = request.getHeader("X-Request-Id");
        String targetVersion = StringUtils.hasText(minSupportedVersion) ? minSupportedVersion : "最新版本";
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error(
                "UPDATE-REQUIRED",
                "当前客户端版本过低，请升级到 " + targetVersion + " 或更高版本后继续使用",
                requestId == null ? "N/A" : requestId
            )
        ));
    }
}
