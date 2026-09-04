package com.smartinsole.measurement;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.global.common.DomainTypes.DataMode;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.PressureFrameRepository.SideCoverage;
import java.time.Duration;
import java.time.Instant;
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
    private final MeasurementQualityRepository qualities;
    private final PressureFrameRepository framesRepository;
    private final ObjectMapper objectMapper;
    private final RealtimeProperties realtimeProperties;

    public QualityService(MeasurementQualityRepository qualities, PressureFrameRepository framesRepository,
                          ObjectMapper objectMapper, RealtimeProperties realtimeProperties) {
        this.qualities = qualities;
        this.framesRepository = framesRepository;
        this.objectMapper = objectMapper;
        this.realtimeProperties = realtimeProperties;
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
        long gaps = stats.getSequenceGapCount();
        boolean recomputeGaps = false;
        Long maxLeft = null;
        Long maxRight = null;
        Long lastLeftTime = null;
        Long lastRightTime = null;
        Set<String> flags = new LinkedHashSet<>(reportedFlags(validFrames));
        for (FootSide side : FootSide.values()) {
            List<PressureFrameData> frames = bySide.getOrDefault(side, List.of());
            if (frames.isEmpty()) continue;
            if (isOutOfOrder(frames)) flags.add("OUT_OF_ORDER");
            SequenceCursor cursor = advanceCursor(frames, stats.lastSequence(side), stats.lastDeviceTimeMs(side),
                    flags);
            gaps += cursor.newGapCount();
            recomputeGaps |= cursor.requiresGapRecompute();
            if (side == FootSide.LEFT) {
                maxLeft = cursor.sequence();
                lastLeftTime = cursor.deviceTimeMs();
            } else {
                maxRight = cursor.sequence();
                lastRightTime = cursor.deviceTimeMs();
            }
            List<PressureFrameData> detectionWindow = frames.size() >= 10 ? frames
                    : framesRepository.findRecentForQuality(sessionId, side, 20);
            if (hasStuckOrSaturatedSensor(detectionWindow)) flags.add("SENSOR_STUCK_OR_SATURATED");
        }
        if (recomputeGaps) {
            // A late frame can close an older gap. The full window query is reserved for this
            // uncommon path; normal 100 Hz forward ingestion remains O(batch-size).
            gaps = framesRepository.countSequenceGaps(sessionId);
        }
        stats.apply(accepted, duplicates, rejected, gaps, maxLeft, maxRight, lastLeftTime, lastRightTime,
                flags, objectMapper, now);
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
        long expectedTotal = expectedPerSide > Long.MAX_VALUE / 2 ? Long.MAX_VALUE : expectedPerSide * 2;
        stats.finalizeForSession(expectedTotal, flags, objectMapper, now);
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

    private static SequenceCursor advanceCursor(List<PressureFrameData> frames, long previousSequence,
                                                long previousDeviceTime, Set<String> flags) {
        long sequence = previousSequence;
        long deviceTime = previousDeviceTime;
        long newGaps = 0;
        boolean requiresGapRecompute = false;
        List<PressureFrameData> ordered = frames.stream()
                .sorted(Comparator.comparingLong(PressureFrameData::sequence)).toList();
        for (PressureFrameData frame : ordered) {
            if (frame.sequence() <= sequence) {
                flags.add("OUT_OF_ORDER");
                requiresGapRecompute = true;
                continue;
            }
            if (deviceTime >= 0 && frame.deviceTimeMs() < deviceTime) {
                flags.add("OUT_OF_ORDER");
            }
            if (deviceTime >= 0 && frame.deviceTimeMs() - deviceTime > 1000) {
                flags.add("DEVICE_TIME_JUMP");
            }
            if (sequence >= 0) {
                newGaps += frame.sequence() - sequence - 1;
            }
            sequence = frame.sequence();
            deviceTime = frame.deviceTimeMs();
        }
        return new SequenceCursor(sequence, deviceTime, newGaps, requiresGapRecompute);
    }

    private static boolean hasStuckOrSaturatedSensor(List<PressureFrameData> frames) {
        if (frames.size() < 10) return false;
        int sensors = frames.getFirst().sensorValues().size();
        for (int sensor = 0; sensor < sensors; sensor++) {
            int expected = frames.getFirst().sensorValues().get(sensor);
            boolean unchanged = true;
            for (PressureFrameData frame : frames) {
                if (frame.sensorValues().get(sensor) != expected) {
                    unchanged = false;
                    break;
                }
            }
            if (unchanged && expected == 65535) return true;
            if (unchanged && expected >= 4095 && anotherSensorChanges(frames, sensor)) {
                return true;
            }
        }
        return false;
    }

    private static boolean anotherSensorChanges(List<PressureFrameData> frames, int excludedSensor) {
        int sensors = frames.getFirst().sensorValues().size();
        for (int sensor = 0; sensor < sensors; sensor++) {
            if (sensor == excludedSensor) continue;
            int first = frames.getFirst().sensorValues().get(sensor);
            for (PressureFrameData frame : frames) {
                if (frame.sensorValues().get(sensor) != first) return true;
            }
        }
        return false;
    }

    private record SequenceCursor(long sequence, long deviceTimeMs, long newGapCount,
                                  boolean requiresGapRecompute) { }
}
