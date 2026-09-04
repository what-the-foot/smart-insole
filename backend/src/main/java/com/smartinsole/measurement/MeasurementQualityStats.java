package com.smartinsole.measurement;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "measurement_quality_stats")
public class MeasurementQualityStats {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", length = 36)
    private UUID sessionId;

    @Column(name = "expected_frame_count", nullable = false)
    private long expectedFrameCount;
    @Column(name = "received_frame_count", nullable = false)
    private long receivedFrameCount;
    @Column(name = "duplicate_frame_count", nullable = false)
    private long duplicateFrameCount;
    @Column(name = "rejected_frame_count", nullable = false)
    private long rejectedFrameCount;
    @Column(name = "sequence_gap_count", nullable = false)
    private long sequenceGapCount;
    @Column(name = "missing_frame_rate", nullable = false)
    private double missingFrameRate;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "flags_json", nullable = false, columnDefinition = "json")
    private String flagsJson;

    @Column(nullable = false)
    private int score;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private QualityLevel level;

    @Column(name = "first_left_sequence")
    private Long firstLeftSequence;
    @Column(name = "first_right_sequence")
    private Long firstRightSequence;
    @Column(name = "last_left_sequence")
    private Long lastLeftSequence;
    @Column(name = "last_right_sequence")
    private Long lastRightSequence;
    @Column(name = "last_left_device_time_ms")
    private Long lastLeftDeviceTimeMs;
    @Column(name = "last_right_device_time_ms")
    private Long lastRightDeviceTimeMs;
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected MeasurementQualityStats() {
    }

    public static MeasurementQualityStats create(UUID sessionId, Instant now) {
        MeasurementQualityStats stats = new MeasurementQualityStats();
        stats.sessionId = sessionId;
        stats.flagsJson = "[]";
        stats.score = 100;
        stats.level = QualityLevel.GOOD;
        stats.updatedAt = now;
        return stats;
    }

    public void apply(int accepted, int duplicates, int rejected, long newGaps,
                      Long maxLeft, Long maxRight, Long lastLeftDeviceTime, Long lastRightDeviceTime,
                      Set<String> newFlags,
                      ObjectMapper objectMapper, Instant now) {
        receivedFrameCount += accepted;
        duplicateFrameCount += duplicates;
        rejectedFrameCount += rejected;
        sequenceGapCount = newGaps;
        if (maxLeft != null && (lastLeftSequence == null || maxLeft > lastLeftSequence)) {
            lastLeftSequence = maxLeft;
        }
        if (maxRight != null && (lastRightSequence == null || maxRight > lastRightSequence)) {
            lastRightSequence = maxRight;
        }
        if (lastLeftDeviceTime != null) {
            lastLeftDeviceTimeMs = lastLeftDeviceTime;
        }
        if (lastRightDeviceTime != null) {
            lastRightDeviceTimeMs = lastRightDeviceTime;
        }
        expectedFrameCount = receivedFrameCount + sequenceGapCount;
        recalculate(newFlags, objectMapper, now);
    }

    public void finalizeForSession(long minimumExpectedFrames, Set<String> finalFlags,
                                   ObjectMapper objectMapper, Instant now) {
        expectedFrameCount = Math.max(receivedFrameCount + sequenceGapCount, minimumExpectedFrames);
        recalculate(finalFlags, objectMapper, now);
    }

    private void recalculate(Set<String> newFlags, ObjectMapper objectMapper, Instant now) {
        long missingFrames = Math.max(0, expectedFrameCount - receivedFrameCount);
        missingFrameRate = expectedFrameCount == 0 ? 0.0 : (double) missingFrames / expectedFrameCount;
        Set<String> flags = flags(objectMapper);
        flags.addAll(newFlags);
        if (sequenceGapCount > 0) {
            flags.add("SEQUENCE_GAP");
        } else {
            flags.remove("SEQUENCE_GAP");
        }
        double total = Math.max(1.0, receivedFrameCount + duplicateFrameCount + rejectedFrameCount);
        double penalty = missingFrameRate * 60.0
                + ((double) duplicateFrameCount / total) * 10.0
                + ((double) rejectedFrameCount / total) * 20.0;
        if (flags.contains("SENSOR_STUCK_OR_SATURATED")) penalty += 20;
        if (flags.contains("OUT_OF_ORDER")) penalty += 5;
        if (flags.contains("LEFT_DATA_MISSING")) penalty += 25;
        if (flags.contains("RIGHT_DATA_MISSING")) penalty += 25;
        if (flags.contains("LEFT_DATA_INCOMPLETE")) penalty += 10;
        if (flags.contains("RIGHT_DATA_INCOMPLETE")) penalty += 10;
        if (flags.contains("LEFT_DEVICE_DISCONNECTED")) penalty += 15;
        if (flags.contains("RIGHT_DEVICE_DISCONNECTED")) penalty += 15;
        // Device-reported conditions (schemaVersion 1.1 flags / dataMode). IMU and battery reports are
        // informational for pressure quality and only FSR errors and filtered data reduce the score.
        if (flags.contains("FSR_ERROR_REPORTED")) penalty += 10;
        if (flags.contains("FILTERED_DATA_MODE")) penalty += 5;
        score = (int) Math.round(Math.max(0, Math.min(100, 100 - penalty)));
        level = score >= 85 ? QualityLevel.GOOD : score >= 60 ? QualityLevel.ACCEPTABLE : QualityLevel.POOR;
        try {
            flagsJson = objectMapper.writeValueAsString(flags);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
        updatedAt = now;
    }

    public long lastSequence(FootSide side) {
        Long value = side == FootSide.LEFT ? lastLeftSequence : lastRightSequence;
        return value == null ? -1 : value;
    }

    public long firstSequence(FootSide side) {
        Long value = side == FootSide.LEFT ? firstLeftSequence : firstRightSequence;
        return value == null ? -1 : value;
    }

    public long lastDeviceTimeMs(FootSide side) {
        Long value = side == FootSide.LEFT ? lastLeftDeviceTimeMs : lastRightDeviceTimeMs;
        return value == null ? -1 : value;
    }

    public Set<String> flags(ObjectMapper mapper) {
        try {
            return new LinkedHashSet<>(mapper.readValue(flagsJson, new TypeReference<Set<String>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored quality flags are invalid", exception);
        }
    }

    public UUID getSessionId() { return sessionId; }
    public long getExpectedFrameCount() { return expectedFrameCount; }
    public long getReceivedFrameCount() { return receivedFrameCount; }
    public long getDuplicateFrameCount() { return duplicateFrameCount; }
    public long getRejectedFrameCount() { return rejectedFrameCount; }
    public long getSequenceGapCount() { return sequenceGapCount; }
    public double getMissingFrameRate() { return missingFrameRate; }
    public String getFlagsJson() { return flagsJson; }
    public int getScore() { return score; }
    public QualityLevel getLevel() { return level; }
}
