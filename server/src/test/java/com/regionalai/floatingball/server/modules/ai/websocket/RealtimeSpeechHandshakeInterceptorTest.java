package com.regionalai.floatingball.server.modules.ai.websocket;

import com.regionalai.floatingball.server.modules.device.entity.AiDevice;
import com.regionalai.floatingball.server.modules.device.service.DeviceService;
import com.regionalai.floatingball.server.modules.release.service.ReleaseService;
import com.regionalai.floatingball.server.modules.security.service.SecurityRejectionLogService;
import com.regionalai.floatingball.server.security.RequestSignatureVerifier;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.web.socket.WebSocketHandler;

import java.net.URI;
import java.util.HashMap;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSpeechHandshakeInterceptorTest {

    @Test
    void databaseNonceFailureReturnsSecurity503() {
        DeviceService deviceService = mock(DeviceService.class);
        ReleaseService releaseService = mock(ReleaseService.class);
        SecurityRejectionLogService rejectionLogService = mock(SecurityRejectionLogService.class);
        RequestSignatureVerifier verifier = mock(RequestSignatureVerifier.class);
        RequestSignatureVerifier.VerificationResult unavailable =
            mock(RequestSignatureVerifier.VerificationResult.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        AiDevice device = new AiDevice();
        device.setIdDevice("DEV001");
        device.setDevicePublicKey("public-key");
        device.setClientVersion("1.0.0");
        URI uri = URI.create(
            "https://pcie.example/v1/ai/speech/realtime/ws"
                + "?token=token-1&clientVersion=1.0.0&updateChannel=production"
                + "&ts=1700000000000&nonce=nonce-1&sig=signature"
        );
        when(request.getURI()).thenReturn(uri);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(deviceService.findActiveByToken("token-1")).thenReturn(device);
        when(releaseService.isUpdateRequired("production", "1.0.0")).thenReturn(false);
        when(unavailable.isStoreUnavailable()).thenReturn(true);
        when(unavailable.getErrorMessage()).thenReturn("请求安全校验服务暂时不可用");
        when(verifier.verify(
            eq("DEV001"), eq("public-key"), eq("GET"),
            eq("/v1/ai/speech/realtime/ws"), eq("1700000000000"),
            eq("nonce-1"), anyString(), eq("signature")
        )).thenReturn(unavailable);
        RealtimeSpeechHandshakeInterceptor interceptor = new RealtimeSpeechHandshakeInterceptor(
            deviceService,
            releaseService,
            verifier,
            rejectionLogService
        );
        boolean accepted = interceptor.beforeHandshake(
            request,
            response,
            mock(WebSocketHandler.class),
            new HashMap<String, Object>()
        );

        assertFalse(accepted);
        verify(response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        org.junit.jupiter.api.Assertions.assertEquals("1", responseHeaders.getFirst("Retry-After"));
        org.junit.jupiter.api.Assertions.assertEquals(
            "SECURITY-503",
            responseHeaders.getFirst("X-PCIE-Error-Code")
        );
        verify(rejectionLogService).logRejection(any());
    }

    @Test
    void deviceLookupDatabaseFailureReturnsSecurity503() {
        DeviceService deviceService = mock(DeviceService.class);
        ReleaseService releaseService = mock(ReleaseService.class);
        SecurityRejectionLogService rejectionLogService = mock(SecurityRejectionLogService.class);
        RequestSignatureVerifier verifier = mock(RequestSignatureVerifier.class);
        ServerHttpRequest request = mock(ServerHttpRequest.class);
        ServerHttpResponse response = mock(ServerHttpResponse.class);
        HttpHeaders responseHeaders = new HttpHeaders();
        URI uri = URI.create(
            "https://pcie.example/v1/ai/speech/realtime/ws?token=token-1"
        );
        when(request.getURI()).thenReturn(uri);
        when(response.getHeaders()).thenReturn(responseHeaders);
        when(deviceService.findActiveByToken("token-1"))
            .thenThrow(new DataAccessResourceFailureException("database down"));
        RealtimeSpeechHandshakeInterceptor interceptor = new RealtimeSpeechHandshakeInterceptor(
            deviceService,
            releaseService,
            verifier,
            rejectionLogService
        );

        boolean accepted = interceptor.beforeHandshake(
            request,
            response,
            mock(WebSocketHandler.class),
            new HashMap<String, Object>()
        );

        assertFalse(accepted);
        verify(response).setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
        org.junit.jupiter.api.Assertions.assertEquals("1", responseHeaders.getFirst("Retry-After"));
        org.junit.jupiter.api.Assertions.assertEquals(
            "SECURITY-503",
            responseHeaders.getFirst("X-PCIE-Error-Code")
        );
    }
}
