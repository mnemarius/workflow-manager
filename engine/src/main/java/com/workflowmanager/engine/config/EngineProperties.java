package com.workflowmanager.engine.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("engine")
public record EngineProperties(
        Duration leaseDuration, Duration reaperInterval, Duration scheduleSweepInterval) {

    public EngineProperties {
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        reaperInterval = reaperInterval == null ? Duration.ofSeconds(5) : reaperInterval;
        scheduleSweepInterval =
                scheduleSweepInterval == null ? Duration.ofSeconds(5) : scheduleSweepInterval;
    }
}
