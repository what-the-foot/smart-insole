package com.smartinsole.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Optional account that the local profile creates once at startup so a fresh database has a
 * login without going through the signup form. A blank email or password disables it.
 */
@ConfigurationProperties("app.seed-account")
public record SeedAccountProperties(String email, String password, String name) {
    public static final String DEFAULT_NAME = "Admin";

    public SeedAccountProperties {
        email = email == null ? "" : email.trim();
        password = password == null ? "" : password;
        name = name == null || name.isBlank() ? DEFAULT_NAME : name.trim();
    }

    public boolean enabled() {
        return !email.isBlank() && !password.isBlank();
    }
}
