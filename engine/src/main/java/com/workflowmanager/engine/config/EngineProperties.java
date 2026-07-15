package com.workflowmanager.engine.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("engine")
public record EngineProperties(Duration leaseDuration, Duration reaperInterval) {

    public EngineProperties {
        leaseDuration = leaseDuration == null ? Duration.ofSeconds(30) : leaseDuration;
        reaperInterval = reaperInterval == null ? Duration.ofSeconds(5) : reaperInterval;
    }
}
