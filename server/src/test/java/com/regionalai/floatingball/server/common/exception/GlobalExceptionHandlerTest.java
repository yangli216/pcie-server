package com.regionalai.floatingball.server.common.exception;

import com.regionalai.floatingball.server.common.api.ApiResponse;
import org.junit.jupiter.api.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GlobalExceptionHandlerTest {

    @Test
    void serviceBusyIncludesRetryAfterAndStableErrorCode() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        HttpServletRequest request = mock(HttpServletRequest.class);
        HttpServletResponse response = mock(HttpServletResponse.class);
        when(request.getMethod()).thenReturn("POST");
        when(request.getRequestURI()).thenReturn("/v1/ai/chat");
        when(request.getHeader("X-Request-Id")).thenReturn("request-1");

        ApiResponse<Void> result = handler.handleServiceBusy(
            new ServiceBusyException("AI请求繁忙，请稍后重试", 2),
            request,
            response
        );

        verify(response).setHeader("Retry-After", "2");
        assertThat(result.getCode()).isEqualTo("AI-BUSY");
        assertThat(result.getRequestId()).isEqualTo("request-1");
    }
}
