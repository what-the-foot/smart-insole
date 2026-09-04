package com.smartinsole.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.UUID;

public final class AuthDtos {
    private AuthDtos() {
    }

    public record SignupRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 100) String password,
            @NotBlank @Size(max = 50) String name
    ) {
    }

    public record SigninRequest(
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(max = 100) String password
    ) {
    }

    public record UserSummary(UUID userId, String email, String name, Instant createdAt) {
    }

    public record AuthTokenResponse(
            String tokenType,
            String accessToken,
            long expiresInSeconds,
            UserSummary user
    ) {
    }
}
