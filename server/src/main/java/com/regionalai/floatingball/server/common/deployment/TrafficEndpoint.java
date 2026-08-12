package com.regionalai.floatingball.server.common.deployment;

import com.regionalai.floatingball.server.common.metrics.RealtimeSpeechMetrics;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

@Component
@Endpoint(id = "traffic")
public class TrafficEndpoint {

    private final TrafficManager trafficManager;
    private final DeploymentProperties deploymentProperties;
    private final RealtimeSpeechMetrics realtimeSpeechMetrics;

    public TrafficEndpoint(TrafficManager trafficManager,
                           DeploymentProperties deploymentProperties,
                           RealtimeSpeechMetrics realtimeSpeechMetrics) {
        this.trafficManager = trafficManager;
        this.deploymentProperties = deploymentProperties;
        this.realtimeSpeechMetrics = realtimeSpeechMetrics;
    }

    @ReadOperation
    public Map<String, Object> read() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        boolean draining = trafficManager.isDraining();
        response.put("nodeId", deploymentProperties.getNodeId());
        response.put("deploymentMode", deploymentProperties.normalizedMode());
        response.put("traffic", draining ? "draining" : "accepting");
        int httpInFlight = trafficManager.httpInFlight();
        int webSocketInFlight = realtimeSpeechMetrics.activeSessions();
        int inFlight = httpInFlight + webSocketInFlight;
        response.put("httpInFlight", httpInFlight);
        response.put("webSocketInFlight", webSocketInFlight);
        response.put("inFlight", inFlight);
        response.put("drained", draining && inFlight == 0);
        return response;
    }

    @WriteOperation
    public Map<String, Object> change(@Selector String action) {
        String normalizedAction = DeploymentProperties.normalize(action);
        if ("drain".equals(normalizedAction)) {
            trafficManager.drain();
        } else if ("resume".equals(normalizedAction)) {
            trafficManager.resume();
        } else {
            throw new IllegalArgumentException("traffic action must be drain or resume");
        }
        return read();
    }
}
