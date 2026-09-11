package com.smartinsole.device.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartinsole.global.common.DomainTypes.DeviceStatus;
import com.smartinsole.global.common.DomainTypes.FootSide;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceHeartbeatTest {
    @Test
    void ignoresDelayedAndEqualHeartbeats() {
        Instant registeredAt = Instant.parse("2026-09-02T10:00:00Z");
        Device device = Device.register(UUID.randomUUID(), "SERIAL-1", "Left", FootSide.LEFT,
                8, "layout-v1", "1.0.0", 4095, registeredAt);
        Instant latest = registeredAt.plusSeconds(60);

        assertThat(device.heartbeat(true, latest)).isTrue();
        assertThat(device.heartbeat(false, latest.minusSeconds(30))).isFalse();
        assertThat(device.heartbeat(false, latest)).isFalse();

        assertThat(device.getStatus()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(device.getLastSeenAt()).isEqualTo(latest);
    }
}
