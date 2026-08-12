package com.regionalai.floatingball.server.common.deployment;

import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.context.ApplicationContext;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class TrafficManager {

    private final ApplicationContext applicationContext;
    private final AtomicBoolean draining = new AtomicBoolean(false);
    private final AtomicInteger httpInFlight = new AtomicInteger();

    public TrafficManager(ApplicationContext applicationContext) {
        this.applicationContext = applicationContext;
    }

    public boolean isDraining() {
        return draining.get();
    }

    public int httpInFlight() {
        return httpInFlight.get();
    }

    synchronized boolean tryAcceptRequest() {
        if (draining.get()) {
            return false;
        }
        httpInFlight.incrementAndGet();
        return true;
    }

    void requestCompleted() {
        httpInFlight.updateAndGet(current -> Math.max(0, current - 1));
    }

    public synchronized void drain() {
        draining.set(true);
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.REFUSING_TRAFFIC);
    }

    public synchronized void resume() {
        draining.set(false);
        AvailabilityChangeEvent.publish(applicationContext, ReadinessState.ACCEPTING_TRAFFIC);
    }
}
