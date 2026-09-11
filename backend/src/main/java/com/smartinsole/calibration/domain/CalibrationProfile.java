package com.smartinsole.calibration.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "calibration_profiles")
public class CalibrationProfile {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "device_id", nullable = false, length = 36)
    private UUID deviceId;

    @Column(nullable = false, length = 50)
    private String version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "baseline_values_json", nullable = false, columnDefinition = "json")
    private String baselineValuesJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "scale_values_json", nullable = false, columnDefinition = "json")
    private String scaleValuesJson;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected CalibrationProfile() {
    }

    private CalibrationProfile(UUID id, UUID deviceId, String version, String baselineValuesJson,
                               String scaleValuesJson, boolean active, Instant createdAt) {
        this.id = id;
        this.deviceId = deviceId;
        this.version = version;
        this.baselineValuesJson = baselineValuesJson;
        this.scaleValuesJson = scaleValuesJson;
        this.active = active;
        this.createdAt = createdAt;
    }

    public static CalibrationProfile identity(UUID deviceId, String baselines, String scales, Instant now) {
        return new CalibrationProfile(UUID.randomUUID(), deviceId, "identity-v1", baselines, scales, true, now);
    }

    public UUID getId() { return id; }
    public UUID getDeviceId() { return deviceId; }
    public String getVersion() { return version; }
    public String getBaselineValuesJson() { return baselineValuesJson; }
    public String getScaleValuesJson() { return scaleValuesJson; }
    public boolean isActive() { return active; }
    public Instant getCreatedAt() { return createdAt; }
}
