package com.regionalai.floatingball.server.modules.ai.websocket;

import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.release.dto.ReleasePolicyView;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import com.regionalai.floatingball.server.security.RequestSignatureVerifier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.server.HandshakeInterceptor;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.io.UnsupportedEncodingException;
import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class RealtimeSpeechHandshakeInterceptor implements HandshakeInterceptor {

    private static final Logger log = LoggerFactory.getLogger(RealtimeSpeechHandshakeInterceptor.class);
    private static final String EMPTY_STRING_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    public static final String DEVICE_ATTRIBUTE = "aiDevice";

    private final DeviceService deviceService;
    private final ReleaseService releaseService;
    private final RequestSignatureVerifier signatureVerifier;
    private final SecurityRejectionLogService rejectionLogService;

    public RealtimeSpeechHandshakeInterceptor(DeviceService deviceService,
                                               ReleaseService releaseService,
                                               RequestSignatureVerifier signatureVerifier,
                                               SecurityRejectionLogService rejectionLogService) {
        this.deviceService = deviceService;
        this.releaseService = releaseService;
        this.signatureVerifier = signatureVerifier;
        this.rejectionLogService = rejectionLogService;
    }

    @Override
    public boolean beforeHandshake(ServerHttpRequest request,
                                   ServerHttpResponse response,
                                   WebSocketHandler wsHandler,
                                   Map<String, Object> attributes) {
        String token = resolveQueryParam(request.getURI(), "token");
        if (!StringUtils.hasText(token)) {
            log.warn("realtime speech ws handshake rejected: missing token. uri={}", safeUri(request.getURI()));
            recordWsRejection("WS_AUTH_MISSING_TOKEN", request, null, "缺少设备令牌", "Token query param missing", false, null, null);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        AiDevice device;
        try {
            device = deviceService.findActiveByToken(token);
        } catch (DataAccessException ex) {
            log.error("realtime speech ws handshake dependency unavailable. uri={}", safeUri(request.getURI()), ex);
            response.getHeaders().set("Retry-After", "1");
            response.getHeaders().set("X-PCIE-Error-Code", "SECURITY-503");
            response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
            return false;
        }
        if (device == null) {
            log.warn("realtime speech ws handshake rejected: invalid token. uri={}", safeUri(request.getURI()));
            recordWsRejection("WS_AUTH_INVALID_TOKEN", request, null, "设备令牌无效", "Token does not match any active device", false, null, null);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        String updateChannel = resolveQueryParam(request.getURI(), "updateChannel");
        String clientVersion = resolveQueryParam(request.getURI(), "clientVersion");
        if (!StringUtils.hasText(clientVersion)) {
            clientVersion = device.getClientVersion();
        }
        if (releaseService.isUpdateRequired(updateChannel, clientVersion)) {
            ReleasePolicyView policy = releaseService.getRequiredPolicy(updateChannel);
            log.warn("realtime speech ws handshake rejected: client version too old. deviceId={}, currentVersion={}, minSupported={}",
                device.getIdDevice(), clientVersion, policy.getMinSupportedVersion());
            recordWsRejection("WS_VERSION_OUTDATED", request, device, "客户端版本过低", "minSupported=" + policy.getMinSupportedVersion() + " current=" + clientVersion, false, null, null);
            response.setStatusCode(HttpStatus.UPGRADE_REQUIRED);
            return false;
        }

        if (StringUtils.hasText(device.getDevicePublicKey())) {
            String ts = resolveQueryParam(request.getURI(), "ts");
            String nonce = resolveQueryParam(request.getURI(), "nonce");
            String sig = resolveQueryParam(request.getURI(), "sig");

            boolean hasSigParams = ts != null && nonce != null && sig != null;

            if (!hasSigParams) {
                log.warn("realtime speech ws handshake rejected: missing signature params. uri={}", safeUri(request.getURI()));
                recordWsRejection("WS_SIG_MISSING", request, device, "缺少WebSocket签名参数", "Expected ts/nonce/sig query params", false, ts, nonce);
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }

            String path = request.getURI().getPath();

            RequestSignatureVerifier.VerificationResult result = signatureVerifier.verify(
                device.getIdDevice(), device.getDevicePublicKey(), "GET", path, ts, nonce, EMPTY_STRING_SHA256, sig);

            if (result.isStoreUnavailable()) {
                log.error("realtime speech ws handshake rejected: nonce store unavailable. deviceId={}",
                    device.getIdDevice());
                recordWsRejection("WS_NONCE_STORE_UNAVAILABLE", request, device,
                    "请求安全校验服务不可用", result.getErrorMessage(), true, ts, nonce);
                response.getHeaders().set("Retry-After", "1");
                response.getHeaders().set("X-PCIE-Error-Code", "SECURITY-503");
                response.setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                return false;
            }

            if (!result.isValid()) {
                log.warn("realtime speech ws handshake rejected: signature invalid. uri={}, reason={}", safeUri(request.getURI()), result.getErrorMessage());
                recordWsRejection("WS_SIG_INVALID", request, device, "WebSocket签名验证失败", result.getErrorMessage(), true, ts, nonce);
                response.setStatusCode(HttpStatus.UNAUTHORIZED);
                return false;
            }
        } else {
            log.warn("realtime speech ws handshake rejected: device has no public key. uri={}, deviceId={}", safeUri(request.getURI()), device.getIdDevice());
            recordWsRejection("WS_SIG_NO_PUBLIC_KEY", request, device, "设备未注册公钥", "Device registered without ECDSA public key", false, null, null);
            response.setStatusCode(HttpStatus.UNAUTHORIZED);
            return false;
        }

        log.info("realtime speech ws handshake succeeded. deviceId={}", device.getIdDevice());
        attributes.put(DEVICE_ATTRIBUTE, device);
        return true;
    }

    @Override
    public void afterHandshake(ServerHttpRequest request,
                               ServerHttpResponse response,
                               WebSocketHandler wsHandler,
                               Exception exception) {
    }

    private void recordWsRejection(String type, ServerHttpRequest request, AiDevice device,
                                    String reason, String detail, boolean hasSignature,
                                    String timestamp, String nonce) {
        String clientIp = resolveClientIp(request);
        String path = request.getURI().getPath();
        SecurityRejectionLogService.RejectionRecord record = SecurityRejectionLogService.RejectionRecord
            .of(type, "GET", path, clientIp)
            .device(device)
            .reason(reason)
            .detail(detail)
            .signature(hasSignature, timestamp, nonce);
        rejectionLogService.logRejection(record);
    }

    private String resolveClientIp(ServerHttpRequest request) {
        InetSocketAddress remoteAddress = request.getRemoteAddress();
        return remoteAddress != null ? remoteAddress.getAddress().getHostAddress() : "unknown";
    }

    private String safeUri(URI uri) {
        String path = uri == null ? "" : uri.getPath();
        String rawQuery = uri == null ? null : uri.getRawQuery();
        if (!StringUtils.hasText(rawQuery)) {
            return path;
        }
        StringBuilder safeQuery = new StringBuilder();
        String[] pairs = rawQuery.split("&");
        for (String pair : pairs) {
            int equalsIndex = pair.indexOf('=');
            String rawName = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
            String name = decode(rawName);
            if (safeQuery.length() > 0) {
                safeQuery.append('&');
            }
            safeQuery.append(rawName);
            if (equalsIndex >= 0) {
                safeQuery.append('=');
                if ("token".equals(name) || "sig".equals(name)) {
                    safeQuery.append("***");
                } else {
                    safeQuery.append(pair.substring(equalsIndex + 1));
                }
            }
        }
        return path + "?" + safeQuery;
    }

    private String resolveQueryParam(URI uri, String name) {
        String query = uri.getRawQuery();
        if (!StringUtils.hasText(query)) {
            return null;
        }
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int equalsIndex = pair.indexOf('=');
            String rawName = equalsIndex >= 0 ? pair.substring(0, equalsIndex) : pair;
            if (!name.equals(decode(rawName))) {
                continue;
            }
            String rawValue = equalsIndex >= 0 ? pair.substring(equalsIndex + 1) : "";
            return decode(rawValue);
        }
        return null;
    }

    private String decode(String value) {
        try {
            return URLDecoder.decode(value, StandardCharsets.UTF_8.name());
        } catch (UnsupportedEncodingException ex) {
            return value;
        }
    }
}
