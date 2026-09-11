package com.smartinsole.analysis.domain;

import com.smartinsole.global.common.DomainTypes.QualityLevel;
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
@Table(name = "analysis_results")
public class AnalysisResult {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(length = 36)
    private UUID id;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", nullable = false, length = 36)
    private UUID sessionId;

    @Column(name = "algorithm_version", nullable = false, length = 50)
    private String algorithmVersion;

    @JdbcTypeCode(SqlTypes.SMALLINT)
    @Column(name = "quality_score", nullable = false)
    private int qualityScore;

    @Enumerated(EnumType.STRING)
    @Column(name = "quality_level", nullable = false, length = 16)
    private QualityLevel qualityLevel;

    @Column(name = "missing_frame_rate", nullable = false)
    private double missingFrameRate;
    @Column(nullable = false)
    private double cadence;
    @Column(name = "left_contact_time_ms", nullable = false)
    private double leftContactTimeMs;
    @Column(name = "right_contact_time_ms", nullable = false)
    private double rightContactTimeMs;
    @Column(name = "symmetry_index", nullable = false)
    private double symmetryIndex;
    @Column(name = "valid_step_count")
    private Integer validStepCount;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "pressure_distribution_json", nullable = false, columnDefinition = "json")
    private String pressureDistributionJson;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "quality_flags_json", nullable = false, columnDefinition = "json")
    private String qualityFlagsJson;

    /** rule-v1.2.0 observation summary (V7); null for results stored by earlier versions. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "observation_summary_json", columnDefinition = "json")
    private String observationSummaryJson;

    // rule-v1.3.0 session-level gait metrics (V8, DOUBLE NULL). Null for results stored by earlier versions
    // and whenever the analyzer could not derive them (no contact window on a foot, fewer than two windows).
    @Column(name = "left_load_share_pct")
    private Double leftLoadSharePct;
    @Column(name = "right_load_share_pct")
    private Double rightLoadSharePct;
    @Column(name = "left_stride_time_ms")
    private Double leftStrideTimeMs;
    @Column(name = "right_stride_time_ms")
    private Double rightStrideTimeMs;
    @Column(name = "mean_stride_time_ms")
    private Double meanStrideTimeMs;

    /**
     * rule-v1.4.0 IMU shank movement summary (V9, JSON NULL): the whole MovementSummary object. Null for
     * results stored by earlier versions and for sessions without IMU frames.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "movement_summary_json", columnDefinition = "json")
    private String movementSummaryJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AnalysisResult() {
    }

    public AnalysisResult(UUID sessionId, String algorithmVersion, int qualityScore, QualityLevel qualityLevel,
                          double missingFrameRate, double cadence, double leftContactTimeMs,
                          double rightContactTimeMs, double symmetryIndex, Integer validStepCount,
                          String pressureDistributionJson, String qualityFlagsJson, Instant createdAt) {
        this(sessionId, algorithmVersion, qualityScore, qualityLevel, missingFrameRate, cadence, leftContactTimeMs,
                rightContactTimeMs, symmetryIndex, validStepCount, pressureDistributionJson, qualityFlagsJson,
                null, createdAt);
    }

    /** rule-v1.2.0 shape: observation summary present, no session-level gait metrics. */
    public AnalysisResult(UUID sessionId, String algorithmVersion, int qualityScore, QualityLevel qualityLevel,
                          double missingFrameRate, double cadence, double leftContactTimeMs,
                          double rightContactTimeMs, double symmetryIndex, Integer validStepCount,
                          String pressureDistributionJson, String qualityFlagsJson,
                          String observationSummaryJson, Instant createdAt) {
        this(sessionId, algorithmVersion, qualityScore, qualityLevel, missingFrameRate, cadence, leftContactTimeMs,
                rightContactTimeMs, symmetryIndex, validStepCount, pressureDistributionJson, qualityFlagsJson,
                observationSummaryJson, null, createdAt);
    }

    /**
     * rule-v1.3.0 shape. {@code gaitMetrics} may be null (legacy) or carry null members when the analyzer
     * could not derive a value; the load share is additionally kept inside pressureDistributionJson.
     */
    public AnalysisResult(UUID sessionId, String algorithmVersion, int qualityScore, QualityLevel qualityLevel,
                          double missingFrameRate, double cadence, double leftContactTimeMs,
                          double rightContactTimeMs, double symmetryIndex, Integer validStepCount,
                          String pressureDistributionJson, String qualityFlagsJson,
                          String observationSummaryJson, GaitMetrics gaitMetrics, Instant createdAt) {
        this(sessionId, algorithmVersion, qualityScore, qualityLevel, missingFrameRate, cadence, leftContactTimeMs,
                rightContactTimeMs, symmetryIndex, validStepCount, pressureDistributionJson, qualityFlagsJson,
                observationSummaryJson, gaitMetrics, null, createdAt);
    }

    /**
     * rule-v1.4.0 shape: adds the IMU shank movement summary JSON (V9). {@code movementSummaryJson} is null
     * when the session carried no IMU frames.
     */
    public AnalysisResult(UUID sessionId, String algorithmVersion, int qualityScore, QualityLevel qualityLevel,
                          double missingFrameRate, double cadence, double leftContactTimeMs,
                          double rightContactTimeMs, double symmetryIndex, Integer validStepCount,
                          String pressureDistributionJson, String qualityFlagsJson,
                          String observationSummaryJson, GaitMetrics gaitMetrics, String movementSummaryJson,
                          Instant createdAt) {
        this.id = UUID.randomUUID();
        this.observationSummaryJson = observationSummaryJson;
        this.movementSummaryJson = movementSummaryJson;
        if (gaitMetrics != null) {
            this.leftLoadSharePct = gaitMetrics.leftLoadSharePct();
            this.rightLoadSharePct = gaitMetrics.rightLoadSharePct();
            this.leftStrideTimeMs = gaitMetrics.leftStrideTimeMs();
            this.rightStrideTimeMs = gaitMetrics.rightStrideTimeMs();
            this.meanStrideTimeMs = gaitMetrics.meanStrideTimeMs();
        }
        this.sessionId = sessionId;
        this.algorithmVersion = algorithmVersion;
        this.qualityScore = qualityScore;
        this.qualityLevel = qualityLevel;
        this.missingFrameRate = missingFrameRate;
        this.cadence = cadence;
        this.leftContactTimeMs = leftContactTimeMs;
        this.rightContactTimeMs = rightContactTimeMs;
        this.symmetryIndex = symmetryIndex;
        this.validStepCount = validStepCount;
        this.pressureDistributionJson = pressureDistributionJson;
        this.qualityFlagsJson = qualityFlagsJson;
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getSessionId() { return sessionId; }
    public String getAlgorithmVersion() { return algorithmVersion; }
    public int getQualityScore() { return qualityScore; }
    public QualityLevel getQualityLevel() { return qualityLevel; }
    public double getMissingFrameRate() { return missingFrameRate; }
    public double getCadence() { return cadence; }
    public double getLeftContactTimeMs() { return leftContactTimeMs; }
    public double getRightContactTimeMs() { return rightContactTimeMs; }
    public double getSymmetryIndex() { return symmetryIndex; }
    public Integer getValidStepCount() { return validStepCount; }
    public String getPressureDistributionJson() { return pressureDistributionJson; }
    public String getQualityFlagsJson() { return qualityFlagsJson; }
    public String getObservationSummaryJson() { return observationSummaryJson; }
    public Double getLeftLoadSharePct() { return leftLoadSharePct; }
    public Double getRightLoadSharePct() { return rightLoadSharePct; }
    public Double getLeftStrideTimeMs() { return leftStrideTimeMs; }
    public Double getRightStrideTimeMs() { return rightStrideTimeMs; }
    public Double getMeanStrideTimeMs() { return meanStrideTimeMs; }
    public String getMovementSummaryJson() { return movementSummaryJson; }
    public Instant getCreatedAt() { return createdAt; }

    /** The five V8 columns as one value; every member is nullable. */
    public record GaitMetrics(Double leftLoadSharePct, Double rightLoadSharePct, Double leftStrideTimeMs,
                              Double rightStrideTimeMs, Double meanStrideTimeMs) { }
}
