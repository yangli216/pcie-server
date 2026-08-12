package com.regionalai.floatingball.server.common.deployment;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.api.ApiResponse;
import com.regionalai.floatingball.server.common.util.RequestIdUtils;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 1)
public class TrafficDrainFilter extends OncePerRequestFilter {

    static final String ERROR_CODE = "NODE-DRAINING";
    static final String RETRY_AFTER_SECONDS = "5";

    private final TrafficManager trafficManager;
    private final ObjectMapper objectMapper;

    public TrafficDrainFilter(TrafficManager trafficManager, ObjectMapper objectMapper) {
        this.trafficManager = trafficManager;
        this.objectMapper = objectMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return "/actuator".equals(uri) || uri.startsWith("/actuator/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (trafficManager.tryAcceptRequest()) {
            InFlightCompletion completion = new InFlightCompletion(trafficManager);
            try {
                filterChain.doFilter(request, response);
            } finally {
                try {
                    if (request.isAsyncStarted()) {
                        completion.listen(request.getAsyncContext());
                    } else {
                        completion.complete();
                    }
                } catch (IllegalStateException ex) {
                    completion.complete();
                }
            }
            return;
        }

        response.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
        response.setHeader("Retry-After", RETRY_AFTER_SECONDS);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(objectMapper.writeValueAsString(
            ApiResponse.error(ERROR_CODE, "当前节点正在摘流，请稍后重试", RequestIdUtils.resolve(request))
        ));
    }

    private static final class InFlightCompletion implements AsyncListener {

        private final TrafficManager trafficManager;
        private final AtomicBoolean completed = new AtomicBoolean(false);

        private InFlightCompletion(TrafficManager trafficManager) {
            this.trafficManager = trafficManager;
        }

        private void listen(AsyncContext asyncContext) {
            try {
                asyncContext.addListener(this);
            } catch (IllegalStateException ex) {
                complete();
            }
        }

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                trafficManager.requestCompleted();
            }
        }

        @Override
        public void onComplete(AsyncEvent event) {
            complete();
        }

        @Override
        public void onTimeout(AsyncEvent event) {
            complete();
        }

        @Override
        public void onError(AsyncEvent event) {
            complete();
        }

        @Override
        public void onStartAsync(AsyncEvent event) {
            listen(event.getAsyncContext());
        }
    }
}
