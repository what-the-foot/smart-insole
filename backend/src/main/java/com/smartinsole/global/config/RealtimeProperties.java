package com.smartinsole.global.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.realtime")
public record RealtimeProperties(int publishHz, Duration disconnectTimeout) {
    public RealtimeProperties {
        if (publishHz < 10 || publishHz > 20) {
            throw new IllegalArgumentException("app.realtime.publish-hz must be between 10 and 20");
        }
        if (disconnectTimeout == null || disconnectTimeout.isZero() || disconnectTimeout.isNegative()) {
            throw new IllegalArgumentException("app.realtime.disconnect-timeout must be positive");
        }
    }
}
