package com.smartinsole.device.dto;

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
            @NotBlank @Size(max = 50) String firmwareVersion,
            /** Optional; only 4095 is accepted and null defaults to it. */
            Integer adcMax
    ) {
        public RegisterDeviceRequest(String serialNumber, String displayName, FootSide footSide,
                                     Integer sensorCount, String sensorLayoutVersion, String firmwareVersion) {
            this(serialNumber, displayName, footSide, sensorCount, sensorLayoutVersion, firmwareVersion, null);
        }
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
            int adcMax,
            DeviceStatus status,
            Instant lastSeenAt,
            Double lastBatteryPercent,
            Integer lastBatteryMv,
            Instant registeredAt
    ) {
    }

    /** {@code label} is the firmware sensor name (S01..S08); legacy layouts carry null. */
    public record SensorPoint(int index, double x, double y, String region, String medialLateral, String label) {
        public SensorPoint(int index, double x, double y, String region, String medialLateral) {
            this(index, x, y, region, medialLateral, null);
        }
    }

    public record SensorLayoutResponse(String version, int sensorCount, List<SensorPoint> points) {
    }

    public record DeviceHeartbeatRequest(
            @NotBlank String receiverId,
            @NotNull Instant observedAt,
            @NotNull Boolean connected,
            Double batteryPercent,
            Integer batteryMv,
            @Size(max = 50) String firmwareVersion,
            Integer rssi
    ) {
        public DeviceHeartbeatRequest(String receiverId, Instant observedAt, Boolean connected,
                                      Double batteryPercent, Integer rssi) {
            this(receiverId, observedAt, connected, batteryPercent, null, null, rssi);
        }
    }
}
