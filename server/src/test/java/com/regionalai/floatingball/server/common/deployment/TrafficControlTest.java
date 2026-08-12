package com.regionalai.floatingball.server.common.deployment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.regionalai.floatingball.server.common.metrics.RealtimeSpeechMetrics;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.mock.web.MockAsyncContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import javax.servlet.AsyncContext;
import javax.servlet.AsyncEvent;
import javax.servlet.AsyncListener;
import javax.servlet.FilterChain;
import javax.servlet.http.HttpServletRequest;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.same;
import static org.mockito.Mockito.times;

class TrafficControlTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void drainAndResumePublishReadinessStateChanges() {
        ApplicationContext context = mock(ApplicationContext.class);
        TrafficManager manager = new TrafficManager(context);

        manager.drain();
        assertTrue(manager.isDraining());
        assertFalse(manager.tryAcceptRequest());
        assertPublishedState(context, ReadinessState.REFUSING_TRAFFIC);
        clearInvocations(context);

        manager.resume();
        assertFalse(manager.isDraining());
        assertTrue(manager.tryAcceptRequest());
        manager.requestCompleted();
        assertPublishedState(context, ReadinessState.ACCEPTING_TRAFFIC);
    }

    @Test
    void endpointSupportsReadDrainAndResumeActions() {
        TrafficManager manager = mock(TrafficManager.class);
        RealtimeSpeechMetrics realtimeSpeechMetrics = RealtimeSpeechMetrics.noop();
        DeploymentProperties deployment = new DeploymentProperties();
        deployment.setMode(DeploymentProperties.AI_SCALE_OUT);
        deployment.setNodeId("node-a");
        TrafficEndpoint endpoint = new TrafficEndpoint(manager, deployment, realtimeSpeechMetrics);

        endpoint.change("drain");
        verify(manager).drain();

        when(manager.isDraining()).thenReturn(true);
        Map<String, Object> draining = endpoint.read();
        assertEquals("node-a", draining.get("nodeId"));
        assertEquals("ai-scale-out", draining.get("deploymentMode"));
        assertEquals("draining", draining.get("traffic"));
        assertEquals(0, draining.get("httpInFlight"));
        assertEquals(0, draining.get("webSocketInFlight"));
        assertEquals(0, draining.get("inFlight"));
        assertEquals(true, draining.get("drained"));

        endpoint.change("resume");
        verify(manager).resume();
        assertThrows(IllegalArgumentException.class, () -> endpoint.change("stop"));
    }

    @Test
    void drainFilterRejectsNewRequestsBeforeOtherFilters() throws Exception {
        TrafficManager manager = mock(TrafficManager.class);
        when(manager.isDraining()).thenReturn(true);
        when(manager.tryAcceptRequest()).thenReturn(false);
        TrafficDrainFilter filter = new TrafficDrainFilter(manager, objectMapper);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/ai/chat");
        request.addHeader("X-Request-Id", "request-a");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        assertEquals(503, response.getStatus());
        assertEquals(TrafficDrainFilter.RETRY_AFTER_SECONDS, response.getHeader("Retry-After"));
        JsonNode payload = objectMapper.readTree(response.getContentAsByteArray());
        assertEquals(TrafficDrainFilter.ERROR_CODE, payload.path("code").asText());
        assertEquals("request-a", payload.path("requestId").asText());
        verify(chain, never()).doFilter(request, response);

        Order order = TrafficDrainFilter.class.getAnnotation(Order.class);
        assertEquals(Ordered.HIGHEST_PRECEDENCE + 1, order.value());
    }

    @Test
    void actuatorRemainsReachableWhileDraining() throws Exception {
        TrafficManager manager = mock(TrafficManager.class);
        when(manager.isDraining()).thenReturn(true);
        TrafficDrainFilter filter = new TrafficDrainFilter(manager, objectMapper);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/actuator/traffic/resume");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void acceptingNodeForwardsBusinessRequests() throws Exception {
        TrafficManager manager = mock(TrafficManager.class);
        when(manager.isDraining()).thenReturn(false);
        when(manager.tryAcceptRequest()).thenReturn(true);
        TrafficDrainFilter filter = new TrafficDrainFilter(manager, objectMapper);
        FilterChain chain = mock(FilterChain.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/admin/api/stats");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
    }

    @Test
    void synchronousRequestIsCountedUntilFilterChainReturns() throws Exception {
        TrafficManager manager = new TrafficManager(mock(ApplicationContext.class));
        TrafficDrainFilter filter = new TrafficDrainFilter(manager, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/client/bootstrap");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (acceptedRequest, acceptedResponse) ->
            assertEquals(1, manager.httpInFlight())
        );

        assertEquals(0, manager.httpInFlight());
    }

    @Test
    void asyncRequestRemainsCountedWhileQueuedUntilCompletion() throws Exception {
        TrafficManager manager = new TrafficManager(mock(ApplicationContext.class));
        TrafficDrainFilter filter = new TrafficDrainFilter(manager, objectMapper);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/ai/chat");
        request.setAsyncSupported(true);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (acceptedRequest, acceptedResponse) -> acceptedRequest.startAsync());

        assertEquals(1, manager.httpInFlight());
        MockAsyncContext asyncContext = (MockAsyncContext) request.getAsyncContext();
        asyncContext.complete();
        assertEquals(0, manager.httpInFlight());
    }

    @Test
    void asyncCompletionTimeoutAndErrorReleaseOnlyOnce() throws Exception {
        TrafficManager manager = mock(TrafficManager.class);
        when(manager.tryAcceptRequest()).thenReturn(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AsyncContext asyncContext = mock(AsyncContext.class);
        when(request.getRequestURI()).thenReturn("/v1/ai/chat");
        when(request.isAsyncStarted()).thenReturn(true);
        when(request.getAsyncContext()).thenReturn(asyncContext);
        TrafficDrainFilter filter = new TrafficDrainFilter(manager, objectMapper);

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
        verify(asyncContext).addListener(listenerCaptor.capture());
        AsyncListener listener = listenerCaptor.getValue();
        listener.onTimeout(new AsyncEvent(asyncContext));
        listener.onError(new AsyncEvent(asyncContext));
        listener.onComplete(new AsyncEvent(asyncContext));

        verify(manager, times(1)).requestCompleted();
    }

    @Test
    void listenerFollowsAStartedAsyncCycle() throws Exception {
        TrafficManager manager = mock(TrafficManager.class);
        when(manager.tryAcceptRequest()).thenReturn(true);
        HttpServletRequest request = mock(HttpServletRequest.class);
        AsyncContext firstContext = mock(AsyncContext.class);
        AsyncContext nextContext = mock(AsyncContext.class);
        when(request.getRequestURI()).thenReturn("/v1/ai/chat");
        when(request.isAsyncStarted()).thenReturn(true);
        when(request.getAsyncContext()).thenReturn(firstContext);
        TrafficDrainFilter filter = new TrafficDrainFilter(manager, objectMapper);

        filter.doFilter(request, new MockHttpServletResponse(), mock(FilterChain.class));

        ArgumentCaptor<AsyncListener> listenerCaptor = ArgumentCaptor.forClass(AsyncListener.class);
        verify(firstContext).addListener(listenerCaptor.capture());
        AsyncListener listener = listenerCaptor.getValue();
        listener.onStartAsync(new AsyncEvent(nextContext));
        verify(nextContext).addListener(same(listener));
        listener.onComplete(new AsyncEvent(nextContext));
        verify(manager).requestCompleted();
    }

    @Test
    void activeWebSocketPreventsDrainedState() {
        TrafficManager manager = new TrafficManager(mock(ApplicationContext.class));
        manager.drain();
        RealtimeSpeechMetrics realtimeSpeechMetrics = RealtimeSpeechMetrics.noop();
        assertTrue(realtimeSpeechMetrics.tryAcquire(1));
        TrafficEndpoint endpoint = new TrafficEndpoint(
            manager,
            new DeploymentProperties(),
            realtimeSpeechMetrics
        );

        Map<String, Object> active = endpoint.read();
        assertEquals(1, active.get("webSocketInFlight"));
        assertEquals(1, active.get("inFlight"));
        assertEquals(false, active.get("drained"));

        realtimeSpeechMetrics.release();
        assertEquals(true, endpoint.read().get("drained"));
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void assertPublishedState(ApplicationContext context, ReadinessState expected) {
        ArgumentCaptor<AvailabilityChangeEvent> captor = ArgumentCaptor.forClass(AvailabilityChangeEvent.class);
        verify(context).publishEvent(captor.capture());
        assertEquals(expected, captor.getValue().getState());
    }
}
