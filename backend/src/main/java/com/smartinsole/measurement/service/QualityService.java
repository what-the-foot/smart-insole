package com.smartinsole.measurement.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.global.common.DomainTypes.DataMode;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.ReceiverUploadState;
import com.smartinsole.global.config.IngestionProperties;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.measurement.domain.MeasurementQualityStats;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.dto.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.domain.MeasurementQualityStats.SideCursor;
import com.smartinsole.measurement.repository.MeasurementQualityRepository;
import com.smartinsole.measurement.repository.PressureFrameRepository.SideCoverage;
import com.smartinsole.measurement.repository.PressureFrameRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class QualityService {
    /** Minimum number of consecutive-sequence deltas before the sample rate is judged. */
    static final int MIN_RATE_DELTAS = 4;
    private static final long DEVICE_TIME_JUMP_MS = 1000;
    /** protocolVersion 2 carries a native u32 sequence, so a wrap can no longer be suspected. */
    private static final int WRAP_FREE_PROTOCOL_VERSION = 2;

    private final MeasurementQualityRepository qualities;
    private final PressureFrameRepository framesRepository;
    private final ObjectMapper objectMapper;
    private final RealtimeProperties realtimeProperties;
    private final IngestionProperties ingestionProperties;

    public QualityService(MeasurementQualityRepository qualities, PressureFrameRepository framesRepository,
                          ObjectMapper objectMapper, RealtimeProperties realtimeProperties,
                          IngestionProperties ingestionProperties) {
        this.qualities = qualities;
        this.framesRepository = framesRepository;
        this.objectMapper = objectMapper;
        this.realtimeProperties = realtimeProperties;
        this.ingestionProperties = ingestionProperties;
    }

    /**
     * Applies one accepted batch to the session quality statistics. The session supplies the ADC scale
     * and sample rate that the heuristics are evaluated against.
     */
    public MeasurementQualityStats update(MeasurementSession session, List<PressureFrameData> validFrames,
                                          int accepted, int duplicates, int rejected, Instant now) {
        UUID sessionId = session.getId();
        MeasurementQualityStats stats = qualities.findById(sessionId)
                .orElseGet(() -> MeasurementQualityStats.create(sessionId, now));
        Map<FootSide, List<PressureFrameData>> bySide = validFrames.stream()
                .collect(java.util.stream.Collectors.groupingBy(PressureFrameData::footSide,
                        () -> new EnumMap<>(FootSide.class), java.util.stream.Collectors.toList()));
        Set<String> flags = new LinkedHashSet<>(reportedFlags(validFrames));
        SideCursor left = null;
        SideCursor right = null;
        for (FootSide side : FootSide.values()) {
            List<PressureFrameData> frames = bySide.getOrDefault(side, List.of());
            if (frames.isEmpty()) continue;
            if (isOutOfOrder(frames)) flags.add("OUT_OF_ORDER");
            SideCursor cursor = advanceCursor(frames, stats.lastSequence(side), stats.lastDeviceTimeMs(side),
                    ingestionProperties.sequenceWrapSuspectDistance(), flags);
            if (side == FootSide.LEFT) {
                left = cursor;
            } else {
                right = cursor;
            }
            if (hasSampleRateMismatch(frames, session.getSampleRateHz(),
                    ingestionProperties.sampleRateMismatchTolerance())) {
                flags.add("SAMPLE_RATE_MISMATCH");
            }
            List<PressureFrameData> detectionWindow = frames.size() >= 10 ? frames
                    : framesRepository.findRecentForQuality(sessionId, side, 20);
            if (hasStuckOrSaturatedSensor(detectionWindow, session.getAdcMax())) {
                flags.add("SENSOR_STUCK_OR_SATURATED");
            }
        }
        stats.apply(accepted, duplicates, rejected, left, right, flags, objectMapper, now);
        return qualities.save(stats);
    }

    public MeasurementQualityStats finalizeSession(MeasurementSession session, Instant now) {
        MeasurementQualityStats stats = qualities.findById(session.getId())
                .orElseGet(() -> MeasurementQualityStats.create(session.getId(), now));
        Map<FootSide, SideCoverage> coverage = new EnumMap<>(FootSide.class);
        framesRepository.summarizeCoverage(session.getId()).forEach(value -> coverage.put(value.footSide(), value));
        long expectedPerSide = coverage.values().stream()
                .mapToLong(value -> expectedFrames(value, session.getSampleRateHz()))
                .max().orElse(1);
        long elapsedMs = session.getStartedAt() == null ? 0
                : Math.max(0, Duration.between(session.getStartedAt(), now).toMillis());
        long graceMs = realtimeProperties.disconnectTimeout().toMillis();
        long wallClockExpected = framesForDuration(Math.max(0, elapsedMs - graceMs),
                session.getSampleRateHz());
        long minimumUsefulWindow = Math.max(1, session.getSampleRateHz() / 5);
        expectedPerSide = Math.max(expectedPerSide, Math.max(wallClockExpected, minimumUsefulWindow));
        Set<String> flags = new LinkedHashSet<>();
        for (FootSide side : FootSide.values()) {
            SideCoverage value = coverage.get(side);
            String prefix = side.name();
            if (value == null) {
                flags.add(prefix + "_DATA_MISSING");
                continue;
            }
            if (value.frameCount() * 100 < expectedPerSide * 80) {
                flags.add(prefix + "_DATA_INCOMPLETE");
            }
            if (Duration.between(value.lastReceivedAt(), now).compareTo(
                    realtimeProperties.disconnectTimeout()) > 0) {
                flags.add(prefix + "_DEVICE_DISCONNECTED");
            }
        }
        Integer pendingBatches = session.getReceiverPendingBatches();
        if (session.getReceiverState() == ReceiverUploadState.UPLOADING
                || pendingBatches != null && pendingBatches > 0) {
            // The receiver's last status report says frames are still queued in its Outbox.
            flags.add("RECEIVER_UPLOAD_INCOMPLETE");
        }
        // One authoritative reconciliation of the O(1) running arithmetic against the stored rows. After a
        // suspected sequence wrap the row order is ambiguous, so the running value is kept instead.
        long authoritativeGaps = stats.flags(objectMapper).contains("SEQUENCE_WRAP_SUSPECTED")
                ? stats.getSequenceGapCount()
                : framesRepository.countSequenceGaps(session.getId());
        long expectedTotal = expectedPerSide > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : expectedPerSide * 2;
        stats.finalizeForSession(expectedTotal, authoritativeGaps, flags, objectMapper, now);
        return qualities.save(stats);
    }

    private static long expectedFrames(SideCoverage coverage, int sampleRateHz) {
        long durationMs = Math.max(0, coverage.lastDeviceTimeMs() - coverage.firstDeviceTimeMs());
        return Math.max(coverage.frameCount(), framesForDuration(durationMs, sampleRateHz));
    }

    private static long framesForDuration(long durationMs, int sampleRateHz) {
        long maximum = Long.MAX_VALUE / 2;
        long wholeSeconds = durationMs / 1000;
        long partialFrames = durationMs % 1000 * sampleRateHz / 1000;
        long timeBased = wholeSeconds > (maximum - 1 - partialFrames) / sampleRateHz
                ? maximum : wholeSeconds * sampleRateHz + partialFrames + 1;
        return timeBased;
    }

    /**
     * Quality flags derived from schemaVersion 1.1 frame metadata: the firmware's Sensor Data flag bits
     * (bit0 FSR_ERROR, bit1 IMU_ERROR, bit2 BATTERY_LOW) and a non-RAW data mode.
     */
    static Set<String> reportedFlags(List<PressureFrameData> frames) {
        Set<String> flags = new LinkedHashSet<>();
        for (PressureFrameData frame : frames) {
            Integer bits = frame.flags();
            if (bits != null) {
                if ((bits & 0b001) != 0) flags.add("FSR_ERROR_REPORTED");
                if ((bits & 0b010) != 0) flags.add("IMU_ERROR_REPORTED");
                if ((bits & 0b100) != 0) flags.add("BATTERY_LOW_REPORTED");
            }
            if (frame.dataMode() == DataMode.FILTERED) flags.add("FILTERED_DATA_MODE");
        }
        return flags;
    }

    private static boolean isOutOfOrder(List<PressureFrameData> frames) {
        for (int index = 1; index < frames.size(); index++) {
            PressureFrameData previous = frames.get(index - 1);
            PressureFrameData current = frames.get(index);
            if (current.sequence() < previous.sequence() || current.deviceTimeMs() < previous.deviceTimeMs()) {
                return true;
            }
        }
        return false;
    }

    /**
     * Walks the batch in sequence order against the stored cursor. Forward frames advance the cursor,
     * late frames only lower the first sequence, and a frame whose sequence dropped by at least
     * {@code wrapDistance} while its device clock kept advancing is a suspected u16 wrap the receiver
     * failed to unwrap (never for protocolVersion 2, whose sequence is a native u32).
     */
    static SideCursor advanceCursor(List<PressureFrameData> frames, long previousSequence,
                                    long previousDeviceTime, long wrapDistance, Set<String> flags) {
        long sequence = previousSequence;
        long deviceTime = previousDeviceTime;
        Long first = null;
        Long last = null;
        List<PressureFrameData> ordered = frames.stream()
                .sorted(Comparator.comparingLong(PressureFrameData::sequence)).toList();
        for (PressureFrameData frame : ordered) {
            if (sequence >= 0 && wrapDetectionApplies(frame) && frame.sequence() + wrapDistance <= sequence
                    && frame.deviceTimeMs() > deviceTime) {
                flags.add("SEQUENCE_WRAP_SUSPECTED");
                continue;
            }
            if (frame.sequence() <= sequence) {
                flags.add("OUT_OF_ORDER");
                first = first == null ? frame.sequence() : Math.min(first, frame.sequence());
                continue;
            }
            if (deviceTime >= 0 && frame.deviceTimeMs() < deviceTime) {
                flags.add("OUT_OF_ORDER");
            }
            if (deviceTime >= 0 && frame.deviceTimeMs() - deviceTime > DEVICE_TIME_JUMP_MS) {
                flags.add("DEVICE_TIME_JUMP");
            }
            first = first == null ? frame.sequence() : Math.min(first, frame.sequence());
            last = frame.sequence();
            sequence = frame.sequence();
            deviceTime = frame.deviceTimeMs();
        }
        if (first == null) return null;
        Long lastDeviceTime = last == null ? null : Long.valueOf(deviceTime);
        return new SideCursor(first.longValue(), last, lastDeviceTime);
    }

    private static boolean wrapDetectionApplies(PressureFrameData frame) {
        return frame.protocolVersion() == null || frame.protocolVersion() < WRAP_FREE_PROTOCOL_VERSION;
    }

    /**
     * Compares the median deviceTimeMs delta between consecutive sequences with the session sample
     * period; a relative deviation above {@code tolerance} means the device streams at another rate.
     */
    static boolean hasSampleRateMismatch(List<PressureFrameData> frames, int sampleRateHz, double tolerance) {
        List<PressureFrameData> ordered = frames.stream()
                .sorted(Comparator.comparingLong(PressureFrameData::sequence)).toList();
        List<Long> deltas = new ArrayList<>();
        for (int index = 1; index < ordered.size(); index++) {
            PressureFrameData previous = ordered.get(index - 1);
            PressureFrameData current = ordered.get(index);
            if (current.sequence() == previous.sequence() + 1) {
                deltas.add(current.deviceTimeMs() - previous.deviceTimeMs());
            }
        }
        if (deltas.size() < MIN_RATE_DELTAS) return false;
        deltas.sort(Comparator.naturalOrder());
        double median = deltas.size() % 2 == 1 ? deltas.get(deltas.size() / 2)
                : (deltas.get(deltas.size() / 2 - 1) + deltas.get(deltas.size() / 2)) / 2.0;
        double expectedPeriodMs = 1000.0 / sampleRateHz;
        return Math.abs(median - expectedPeriodMs) > tolerance * expectedPeriodMs;
    }

    /**
     * A sensor that stays pinned at the session ADC ceiling for the whole detection window is either
     * saturated or electrically stuck. Constant low values are normal for an unloaded channel.
     */
    static boolean hasStuckOrSaturatedSensor(List<PressureFrameData> frames, int adcMax) {
        if (frames.size() < 10) return false;
        int sensors = frames.getFirst().sensorValues().size();
        for (int sensor = 0; sensor < sensors; sensor++) {
            int expected = frames.getFirst().sensorValues().get(sensor);
            if (expected < adcMax) continue;
            boolean unchanged = true;
            for (PressureFrameData frame : frames) {
                if (frame.sensorValues().get(sensor) != expected) {
                    unchanged = false;
                    break;
                }
            }
            if (unchanged) return true;
        }
        return false;
    }
}
