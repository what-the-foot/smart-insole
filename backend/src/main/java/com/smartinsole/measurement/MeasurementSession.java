package com.smartinsole.measurement;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.common.DomainTypes.SourceType;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "measurement_sessions")
public class MeasurementSession {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "user_id", nullable = false, length = 36)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "left_device_id", nullable = false, length = 36)
    private UUID leftDeviceId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "right_device_id", nullable = false, length = 36)
    private UUID rightDeviceId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "left_calibration_id", nullable = false, length = 36)
    private UUID leftCalibrationId;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "right_calibration_id", nullable = false, length = 36)
    private UUID rightCalibrationId;

    @Column(name = "left_sensor_layout_version", nullable = false, length = 50)
    private String leftSensorLayoutVersion;

    @Column(name = "right_sensor_layout_version", nullable = false, length = 50)
    private String rightSensorLayoutVersion;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 24)
    private MeasurementStatus status;

    @Column(name = "sample_rate_hz", nullable = false)
    private int sampleRateHz;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 16)
    private SourceType sourceType;

    @Column(length = 500)
    private String memo;

    @Column(name = "data_quality_score")
    private Integer dataQualityScore;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "ended_at")
    private Instant endedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected MeasurementSession() {
    }

    private MeasurementSession(UUID userId, UUID leftDeviceId, UUID rightDeviceId, UUID leftCalibrationId,
                               UUID rightCalibrationId, String leftLayout, String rightLayout,
                               int sampleRateHz, String memo, Instant now) {
        this.id = UUID.randomUUID();
        this.userId = userId;
        this.leftDeviceId = leftDeviceId;
        this.rightDeviceId = rightDeviceId;
        this.leftCalibrationId = leftCalibrationId;
        this.rightCalibrationId = rightCalibrationId;
        this.leftSensorLayoutVersion = leftLayout;
        this.rightSensorLayoutVersion = rightLayout;
        this.status = MeasurementStatus.CREATED;
        this.sampleRateHz = sampleRateHz;
        this.sourceType = SourceType.SIMULATED;
        this.memo = memo;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public static MeasurementSession create(UUID userId, UUID leftDeviceId, UUID rightDeviceId,
                                            UUID leftCalibrationId, UUID rightCalibrationId,
                                            String leftLayout, String rightLayout, int sampleRateHz,
                                            String memo, Instant now) {
        return new MeasurementSession(userId, leftDeviceId, rightDeviceId, leftCalibrationId,
                rightCalibrationId, leftLayout, rightLayout, sampleRateHz, memo, now);
    }

    public void start(Instant now) {
        require(MeasurementStatus.CREATED);
        status = MeasurementStatus.MEASURING;
        startedAt = now;
        updatedAt = now;
    }

    public void complete(Instant now) {
        require(MeasurementStatus.MEASURING);
        status = MeasurementStatus.PROCESSING;
        endedAt = now;
        updatedAt = now;
    }

    public void cancel(Instant now) {
        if (status != MeasurementStatus.CREATED && status != MeasurementStatus.MEASURING) {
            invalidState();
        }
        status = MeasurementStatus.CANCELLED;
        endedAt = now;
        updatedAt = now;
    }

    public void analysisCompleted(int qualityScore, Instant now) {
        require(MeasurementStatus.PROCESSING);
        status = MeasurementStatus.COMPLETED;
        dataQualityScore = qualityScore;
        updatedAt = now;
    }

    public void fail(Instant now) {
        if (status != MeasurementStatus.MEASURING && status != MeasurementStatus.PROCESSING) {
            invalidState();
        }
        status = MeasurementStatus.FAILED;
        endedAt = endedAt == null ? now : endedAt;
        updatedAt = now;
    }

    private void require(MeasurementStatus required) {
        if (status != required) {
            invalidState();
        }
    }

    private void invalidState() {
        throw new BusinessException(ErrorCode.INVALID_SESSION_STATE,
                ErrorCode.INVALID_SESSION_STATE.defaultMessage(), Map.of("currentStatus", status.name()));
    }

    public UUID getId() { return id; }
    public UUID getUserId() { return userId; }
    public UUID getLeftDeviceId() { return leftDeviceId; }
    public UUID getRightDeviceId() { return rightDeviceId; }
    public UUID getLeftCalibrationId() { return leftCalibrationId; }
    public UUID getRightCalibrationId() { return rightCalibrationId; }
    public String getLeftSensorLayoutVersion() { return leftSensorLayoutVersion; }
    public String getRightSensorLayoutVersion() { return rightSensorLayoutVersion; }
    public MeasurementStatus getStatus() { return status; }
    public int getSampleRateHz() { return sampleRateHz; }
    public SourceType getSourceType() { return sourceType; }
    public String getMemo() { return memo; }
    public Integer getDataQualityScore() { return dataQualityScore; }
    public Instant getStartedAt() { return startedAt; }
    public Instant getEndedAt() { return endedAt; }
    public Instant getCreatedAt() { return createdAt; }
}
