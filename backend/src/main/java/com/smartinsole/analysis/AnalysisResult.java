package com.smartinsole.analysis;

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

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected AnalysisResult() {
    }

    public AnalysisResult(UUID sessionId, String algorithmVersion, int qualityScore, QualityLevel qualityLevel,
                          double missingFrameRate, double cadence, double leftContactTimeMs,
                          double rightContactTimeMs, double symmetryIndex, Integer validStepCount,
                          String pressureDistributionJson, String qualityFlagsJson, Instant createdAt) {
        this.id = UUID.randomUUID();
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
    public Instant getCreatedAt() { return createdAt; }
}
