package com.smartinsole.global.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartinsole.global.config.AuthProperties;
import io.jsonwebtoken.ExpiredJwtException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class JwtServiceTest {
    private static final AuthProperties PROPERTIES = new AuthProperties(
            "test-only-jwt-secret-with-at-least-thirty-two-bytes", "test-issuer", 60);

    @Test
    void parserUsesTheSameInjectedClockAsIssuer() {
        Clock clock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        JwtService service = new JwtService(PROPERTIES, clock);
        UUID userId = UUID.randomUUID();

        JwtService.Token token = service.issue(userId, "user@example.com");

        assertThat(service.parse(token.value()).userId()).isEqualTo(userId);
    }

    @Test
    void parserRejectsTokenAfterInjectedClockPassesExpiration() {
        Instant issuedAt = Instant.parse("2030-01-01T00:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(issuedAt, issuedAt.plusSeconds(61));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        JwtService service = new JwtService(PROPERTIES, clock);
        String token = service.issue(UUID.randomUUID(), "user@example.com").value();

        assertThatThrownBy(() -> service.parse(token)).isInstanceOf(ExpiredJwtException.class);
    }
}
