package com.regionalai.floatingball.server.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import com.regionalai.floatingball.server.security.nonce.InMemoryNonceStore;
import com.regionalai.floatingball.server.security.nonce.NonceStoreUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DeviceAuthFilterTest {

    private DeviceService deviceService;
    private ReleaseService releaseService;
    private SecurityRejectionLogService rejectionLogService;
    private DeviceAuthFilter filter;
    private KeyPair keyPair;
    private String publicKeyBase64;

    @BeforeEach
    void setUp() throws Exception {
        deviceService = mock(DeviceService.class);
        releaseService = mock(ReleaseService.class);
        rejectionLogService = mock(SecurityRejectionLogService.class);
        filter = new DeviceAuthFilter(
            deviceService,
            releaseService,
            new RequestSignatureVerifier(new InMemoryNonceStore()),
            rejectionLogService,
            new ObjectMapper()
        );
        KeyPairGenerator keyGen = KeyPairGenerator.getInstance("EC");
        keyGen.initialize(256);
        keyPair = keyGen.generateKeyPair();
        publicKeyBase64 = Base64.getEncoder().encodeToString(keyPair.getPublic().getEncoded());
    }

    @Test
    void shouldRejectWhenDeclaredBodyHashDoesNotMatchActualBody() throws Exception {
        String body = "{\"message\":\"hello\"}";
        String wrongBodyHash = sha256Hex("{\"message\":\"tampered\"}");
        MockHttpServletRequest request = signedRequest(body, wrongBodyHash, wrongBodyHash);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AiDevice device = device();

        when(deviceService.findActiveByToken("token-1")).thenReturn(device);
        when(releaseService.isUpdateRequired(eq("production"), eq("1.0.0"))).thenReturn(false);

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        assertEquals(
            "请求签名验证失败，请重新连接后台；如仍失败，请联系管理员重新注册设备",
            new ObjectMapper().readTree(response.getContentAsString()).get("message").asText()
        );
        assertFalse(response.getContentAsString().contains("摘要不匹配"));
        verify(rejectionLogService).logRejection(any());
    }

    @Test
    void shouldUseActualBodyHashForSignatureVerification() throws Exception {
        String body = "{\"message\":\"hello\"}";
        String actualBodyHash = sha256Hex(body);
        MockHttpServletRequest request = signedRequest(body, actualBodyHash, sha256Hex("{\"message\":\"tampered\"}"));
        MockHttpServletResponse response = new MockHttpServletResponse();
        AiDevice device = device();

        when(deviceService.findActiveByToken("token-1")).thenReturn(device);
        when(releaseService.isUpdateRequired(eq("production"), eq("1.0.0"))).thenReturn(false);

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(401, response.getStatus());
        verify(rejectionLogService).logRejection(any());
    }

    @Test
    void shouldPassAndKeepBodyReadableWhenSignatureMatchesActualBody() throws Exception {
        String body = "{\"message\":\"hello\"}";
        String actualBodyHash = sha256Hex(body);
        MockHttpServletRequest request = signedRequest(body, actualBodyHash, actualBodyHash);
        MockHttpServletResponse response = new MockHttpServletResponse();
        AiDevice device = device();
        MockFilterChain chain = new MockFilterChain();

        when(deviceService.findActiveByToken("token-1")).thenReturn(device);
        when(releaseService.isUpdateRequired(eq("production"), eq("1.0.0"))).thenReturn(false);

        filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        verify(rejectionLogService, never()).logRejection(any());
    }

    @Test
    void shouldReturnSecurity503WhenNonceStoreIsUnavailable() throws Exception {
        String body = "{\"message\":\"hello\"}";
        String bodyHash = sha256Hex(body);
        MockHttpServletRequest request = signedRequest(body, bodyHash, bodyHash);
        request.addHeader("X-Request-Id", "request-503");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(deviceService.findActiveByToken("token-1")).thenReturn(device());
        when(releaseService.isUpdateRequired(eq("production"), eq("1.0.0"))).thenReturn(false);
        filter = new DeviceAuthFilter(
            deviceService,
            releaseService,
            new RequestSignatureVerifier((deviceId, nonce, expiresAtEpochMs) -> {
                throw new NonceStoreUnavailableException(
                    "down",
                    new IllegalStateException("db down")
                );
            }),
            rejectionLogService,
            new ObjectMapper()
        );

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertEquals(
            "SECURITY-503",
            new ObjectMapper().readTree(response.getContentAsString()).get("code").asText()
        );
        verify(rejectionLogService).logRejection(any());
    }

    @Test
    void shouldReturnSecurity503WhenDeviceLookupDatabaseIsUnavailable() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/ai/chat");
        request.addHeader("Authorization", "Bearer token-1");
        request.addHeader("X-Request-Id", "request-device-db-503");
        MockHttpServletResponse response = new MockHttpServletResponse();
        when(deviceService.findActiveByToken("token-1"))
            .thenThrow(new DataAccessResourceFailureException("database down"));

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(503, response.getStatus());
        assertEquals("1", response.getHeader("Retry-After"));
        assertEquals(
            "SECURITY-503",
            new ObjectMapper().readTree(response.getContentAsString()).get("code").asText()
        );
        verify(rejectionLogService, never()).logRejection(any());
    }

    private AiDevice device() {
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setDevicePublicKey(publicKeyBase64);
        device.setClientVersion("1.0.0");
        return device;
    }

    private MockHttpServletRequest signedRequest(String body,
                                                 String declaredBodyHash,
                                                 String signedBodyHash) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String nonce = UUID.randomUUID().toString();
        String signature = sign("POST", "/v1/ai/chat", timestamp, nonce, signedBodyHash);

        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/ai/chat");
        request.setContentType("application/json");
        request.setContent(body.getBytes(StandardCharsets.UTF_8));
        request.addHeader("Authorization", "Bearer token-1");
        request.addHeader("X-Client-Version", "1.0.0");
        request.addHeader("X-Update-Channel", "production");
        request.addHeader("X-Timestamp", timestamp);
        request.addHeader("X-Nonce", nonce);
        request.addHeader("X-Signature", signature);
        request.addHeader("X-Body-SHA256", declaredBodyHash);
        return request;
    }

    private String sign(String method,
                        String path,
                        String timestamp,
                        String nonce,
                        String bodyHash) throws Exception {
        String stringToSign = method + "\n" + path + "\n" + timestamp + "\n" + nonce + "\n" + bodyHash + "\n";
        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(stringToSign.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(signature.sign());
    }

    private static String sha256Hex(String value) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        byte[] hash = digest.digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(hash.length * 2);
        for (byte b : hash) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }
}
