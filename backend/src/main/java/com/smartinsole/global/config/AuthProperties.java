package com.smartinsole.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.auth")
public record AuthProperties(String jwtSecret, String jwtIssuer, long accessTokenSeconds) {
    public AuthProperties {
        if (jwtIssuer == null || jwtIssuer.isBlank()) {
            throw new IllegalArgumentException("app.auth.jwt-issuer must not be blank");
        }
        if (accessTokenSeconds < 1) {
            throw new IllegalArgumentException("app.auth.access-token-seconds must be positive");
        }
    }
}
