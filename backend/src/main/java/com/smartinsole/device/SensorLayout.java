package com.smartinsole.device;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "sensor_layouts")
public class SensorLayout {
    @Id
    @Column(length = 50)
    private String version;

    @Column(name = "sensor_count", nullable = false)
    private int sensorCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "points_json", nullable = false, columnDefinition = "json")
    private String pointsJson;

    @Column(nullable = false)
    private boolean active;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected SensorLayout() {
    }

    public SensorLayout(String version, int sensorCount, String pointsJson, boolean active, Instant createdAt) {
        this.version = version;
        this.sensorCount = sensorCount;
        this.pointsJson = pointsJson;
        this.active = active;
        this.createdAt = createdAt;
    }

    public String getVersion() { return version; }
    public int getSensorCount() { return sensorCount; }
    public String getPointsJson() { return pointsJson; }
    public boolean isActive() { return active; }
}
