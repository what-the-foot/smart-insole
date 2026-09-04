package com.smartinsole.global.config;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.receiver")
public record ReceiverProperties(String apiKey, long maxRequestBytes) {
    public ReceiverProperties {
        if (apiKey == null || apiKey.isBlank()
                || apiKey.getBytes(StandardCharsets.UTF_8).length < 16) {
            throw new IllegalArgumentException("app.receiver.api-key must contain at least 16 UTF-8 bytes");
        }
        if (maxRequestBytes < 1) {
            throw new IllegalArgumentException("app.receiver.max-request-bytes must be positive");
        }
    }
}
