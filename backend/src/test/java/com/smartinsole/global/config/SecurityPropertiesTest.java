package com.smartinsole.global.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.Test;

class SecurityPropertiesTest {
    @Test
    void rejectsBlankOrWeakReceiverKeys() {
        assertThatThrownBy(() -> new ReceiverProperties("", 1024))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReceiverProperties("short", 1024))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsInvalidAuthenticationAndRealtimeDurations() {
        assertThatThrownBy(() -> new AuthProperties("secret", " ", 60))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new AuthProperties("secret", "issuer", 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RealtimeProperties(10, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
