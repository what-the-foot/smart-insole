package com.smartinsole.device;

import com.smartinsole.global.common.DomainTypes.DeviceStatus;
import com.smartinsole.global.common.DomainTypes.FootSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "devices")
public class Device {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @Column(name = "serial_number", nullable = false, unique = true, length = 100)
    private String serialNumber;

    @Column(name = "display_name", nullable = false, length = 100)
    private String displayName;

    @Enumerated(EnumType.STRING)
    @Column(name = "foot_side", nullable = false, length = 16)
    private FootSide footSide;

    @Column(name = "sensor_count", nullable = false)
    private int sensorCount;

    @Column(name = "sensor_layout_version", nullable = false, length = 50)
    private String sensorLayoutVersion;

    @Column(name = "firmware_version", nullable = false, length = 50)
    private String firmwareVersion;

    @Column(name = "adc_max", nullable = false)
    private int adcMax;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private DeviceStatus status;

    @Column(name = "last_seen_at")
    private Instant lastSeenAt;

    @Column(name = "last_battery_percent")
    private Double lastBatteryPercent;

    @Column(name = "last_battery_mv")
    private Integer lastBatteryMv;

    @Column(name = "registered_at", nullable = false)
    private Instant registeredAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Device() {
    }

    private Device(UUID id, UUID userId, String serialNumber, String displayName, FootSide footSide,
                   int sensorCount, String sensorLayoutVersion, String firmwareVersion, int adcMax, Instant now) {
        this.id = id;
        this.userId = userId;
        this.serialNumber = serialNumber;
        this.displayName = displayName;
        this.footSide = footSide;
        this.sensorCount = sensorCount;
        this.sensorLayoutVersion = sensorLayoutVersion;
        this.firmwareVersion = firmwareVersion;
        this.adcMax = adcMax;
        this.status = DeviceStatus.ACTIVE;
        this.registeredAt = now;
        this.updatedAt = now;
    }

    public static Device register(UUID userId, String serialNumber, String displayName, FootSide footSide,
                                  int sensorCount, String sensorLayoutVersion, String firmwareVersion, int adcMax,
                                  Instant now) {
        return new Device(UUID.randomUUID(), userId, serialNumber, displayName, footSide, sensorCount,
                sensorLayoutVersion, firmwareVersion, adcMax, now);
    }

    public boolean heartbeat(boolean connected, Instant observedAt) {
        if (lastSeenAt != null && !observedAt.isAfter(lastSeenAt)) {
            return false;
        }
        this.status = connected ? DeviceStatus.ACTIVE : DeviceStatus.DISCONNECTED;
        this.lastSeenAt = observedAt;
        this.updatedAt = observedAt;
        return true;
    }

    /** Stores the battery values of an accepted heartbeat; a null value leaves the previous reading. */
    public void recordBattery(Double batteryPercent, Integer batteryMv) {
        if (batteryPercent != null) {
            this.lastBatteryPercent = batteryPercent;
        }
        if (batteryMv != null) {
            this.lastBatteryMv = batteryMv;
        }
    }

    /** Updates the firmware version reported by the device Status characteristic. */
    public void recordFirmwareVersion(String reportedVersion) {
        if (reportedVersion != null && !reportedVersion.isBlank()) {
            this.firmwareVersion = reportedVersion.trim();
        }
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public String getSerialNumber() { return serialNumber; }
    public String getDisplayName() { return displayName; }
    public FootSide getFootSide() { return footSide; }
    public int getSensorCount() { return sensorCount; }
    public String getSensorLayoutVersion() { return sensorLayoutVersion; }
    public String getFirmwareVersion() { return firmwareVersion; }
    public int getAdcMax() { return adcMax; }
    public DeviceStatus getStatus() { return status; }
    public Instant getLastSeenAt() { return lastSeenAt; }
    public Double getLastBatteryPercent() { return lastBatteryPercent; }
    public Integer getLastBatteryMv() { return lastBatteryMv; }
    public Instant getRegisteredAt() { return registeredAt; }
}
