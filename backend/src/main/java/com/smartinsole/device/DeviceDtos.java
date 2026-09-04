package com.smartinsole.device;

import com.smartinsole.global.common.DomainTypes.DeviceStatus;
import com.smartinsole.global.common.DomainTypes.FootSide;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DeviceDtos {
    private DeviceDtos() {
    }

    public record RegisterDeviceRequest(
            @NotBlank @Size(max = 100) String serialNumber,
            @NotBlank @Size(max = 100) String displayName,
            @NotNull FootSide footSide,
            @NotNull Integer sensorCount,
            @NotBlank @Size(max = 50) String sensorLayoutVersion,
            @NotBlank @Size(max = 50) String firmwareVersion
    ) {
    }

    public record DeviceResponse(
            UUID deviceId,
            String serialNumber,
            String displayName,
            FootSide footSide,
            int sensorCount,
            String sensorLayoutVersion,
            String activeCalibrationVersion,
            String firmwareVersion,
            DeviceStatus status,
            Instant lastSeenAt,
            Instant registeredAt
    ) {
    }

    public record SensorPoint(int index, double x, double y, String region, String medialLateral) {
    }

    public record SensorLayoutResponse(String version, int sensorCount, List<SensorPoint> points) {
    }

    public record DeviceHeartbeatRequest(
            @NotBlank String receiverId,
            @NotNull Instant observedAt,
            @NotNull Boolean connected,
            Double batteryPercent,
            Integer rssi
    ) {
    }
}
