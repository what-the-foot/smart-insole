package com.smartinsole.global.security;

import java.security.Principal;
import java.time.Instant;
import java.util.UUID;

public record AuthenticatedUser(UUID userId, String email, Instant expiresAt) implements Principal {
    public AuthenticatedUser(UUID userId, String email) {
        this(userId, email, Instant.MAX);
    }

    public boolean isExpired(Instant now) {
        return !expiresAt.isAfter(now);
    }

    @Override
    public String getName() {
        return userId.toString();
    }
}
