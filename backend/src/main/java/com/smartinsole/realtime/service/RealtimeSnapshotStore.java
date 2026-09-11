package com.smartinsole.realtime.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.calibration.domain.CalibrationProfile;
import com.smartinsole.calibration.repository.CalibrationProfileRepository;
import com.smartinsole.device.dto.DeviceDtos.SensorPoint;
import com.smartinsole.device.domain.SensorLayout;
import com.smartinsole.device.repository.SensorLayoutRepository;
import com.smartinsole.global.common.DomainTypes.ContactState;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.measurement.dto.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.repository.MeasurementQualityRepository;
import com.smartinsole.measurement.domain.MeasurementQualityStats;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.realtime.dto.RealtimeDtos.CopPoint;
import com.smartinsole.realtime.dto.RealtimeDtos.FootRealtimeData;
import com.smartinsole.realtime.dto.RealtimeDtos.RealtimePressureMessage;
import com.smartinsole.realtime.dto.RealtimeDtos.RealtimeQuality;
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
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

@Component
public class RealtimeSnapshotStore {
    private final ConcurrentHashMap<UUID, SnapshotState> snapshots = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, CalculationContext> contexts = new ConcurrentHashMap<>();
    private final CalibrationProfileRepository calibrations;
    private final SensorLayoutRepository layouts;
    private final MeasurementQualityRepository qualities;
    private final ObjectMapper objectMapper;
    private final RealtimeProperties realtimeProperties;
    private final AnalysisProperties analysisProperties;

    public RealtimeSnapshotStore(CalibrationProfileRepository calibrations, SensorLayoutRepository layouts,
                                 MeasurementQualityRepository qualities, ObjectMapper objectMapper,
                                 RealtimeProperties realtimeProperties, AnalysisProperties analysisProperties) {
        this.calibrations = calibrations;
        this.layouts = layouts;
        this.qualities = qualities;
        this.objectMapper = objectMapper;
        this.realtimeProperties = realtimeProperties;
        this.analysisProperties = analysisProperties;
    }

    public void update(MeasurementSession session, List<PressureFrameData> frames, Instant receivedAt) {
        CalculationContext context = contexts.computeIfAbsent(session.getId(), ignored -> context(session));
        SnapshotState state = snapshots.computeIfAbsent(session.getId(), ignored -> new SnapshotState());
        Map<FootSide, PressureFrameData> latest = new EnumMap<>(FootSide.class);
        frames.forEach(frame -> latest.merge(frame.footSide(), frame, (first, second) ->
                Comparator.comparingLong(PressureFrameData::deviceTimeMs)
                        .thenComparingLong(PressureFrameData::sequence).compare(first, second) >= 0 ? first : second));
        latest.forEach((side, frame) -> state.update(side, calculate(frame,
                side == FootSide.LEFT ? context.left() : context.right(), context.adcMax(), receivedAt)));
    }

    public RealtimePressureMessage message(MeasurementSession session, Instant now) {
        SnapshotState state = snapshots.computeIfAbsent(session.getId(), ignored -> new SnapshotState());
        MeasurementQualityStats stats = qualities.findById(session.getId()).orElse(null);
        FootRealtimeData left = connected(state.left, now);
        FootRealtimeData right = connected(state.right, now);
        Set<String> flags = stats == null ? new LinkedHashSet<>() : stats.flags(objectMapper);
        int score = stats == null ? 100 : stats.getScore();
        boolean sessionTimedOut = session.getStartedAt() != null
                && Duration.between(session.getStartedAt(), now).compareTo(
                realtimeProperties.disconnectTimeout()) > 0;
        if (left == null && sessionTimedOut) {
            flags.add("LEFT_DATA_MISSING");
            flags.add("LEFT_DEVICE_DISCONNECTED");
            score = Math.max(0, score - 25);
        } else if (left != null && !left.connected()) {
            flags.add("LEFT_DEVICE_DISCONNECTED");
            score = Math.max(0, score - 15);
        }
        if (right == null && sessionTimedOut) {
            flags.add("RIGHT_DATA_MISSING");
            flags.add("RIGHT_DEVICE_DISCONNECTED");
            score = Math.max(0, score - 25);
        } else if (right != null && !right.connected()) {
            flags.add("RIGHT_DEVICE_DISCONNECTED");
            score = Math.max(0, score - 15);
        }
        QualityLevel level = score >= 85 ? QualityLevel.GOOD
                : score >= 60 ? QualityLevel.ACCEPTABLE : QualityLevel.POOR;
        RealtimeQuality quality = new RealtimeQuality(score, level, List.copyOf(flags));
        long elapsed = session.getStartedAt() == null ? 0
                : Math.max(0, Duration.between(session.getStartedAt(), now).toMillis());
        return new RealtimePressureMessage("1.0", session.getId(), now, elapsed, "MEASURING",
                left, right, quality);
    }

    public void clear(UUID sessionId) {
        snapshots.remove(sessionId);
        contexts.remove(sessionId);
    }

    private FootRealtimeData connected(FootRealtimeData value, Instant now) {
        if (value == null) return null;
        boolean connected = !now.isAfter(value.lastReceivedAt().plus(realtimeProperties.disconnectTimeout()));
        if (connected == value.connected()) return value;
        return new FootRealtimeData(connected, value.lastSequence(), value.deviceTimeMs(), value.sensorValues(),
                value.totalPressure(), value.cop(), value.contactState(), value.lastReceivedAt());
    }

    private FootRealtimeData calculate(PressureFrameData frame, FootContext context, int adcMax,
                                       Instant receivedAt) {
        List<Double> normalized = new ArrayList<>(frame.sensorValues().size());
        for (int index = 0; index < frame.sensorValues().size(); index++) {
            double baseline = context.baselines().get(index);
            double scale = context.scales().get(index);
            double calibrated = Math.max(0, (frame.sensorValues().get(index) - baseline) * scale);
            normalized.add(clamp(calibrated * 100.0 / adcMax));
        }
        double total = normalized.stream().mapToDouble(Double::doubleValue).sum();
        CopPoint cop = null;
        if (total > 0) {
            double weightedX = 0;
            double weightedY = 0;
            for (int index = 0; index < normalized.size(); index++) {
                SensorPoint point = context.points().get(index);
                weightedX += point.x() * normalized.get(index);
                weightedY += point.y() * normalized.get(index);
            }
            cop = new CopPoint(clamp01(weightedX / total), clamp01(weightedY / total));
        }
        ContactState contact = total >= analysisProperties.contactThreshold(normalized.size())
                ? ContactState.CONTACT : ContactState.NO_CONTACT;
        return new FootRealtimeData(true, frame.sequence(), frame.deviceTimeMs(), List.copyOf(normalized), total,
                cop, contact, receivedAt);
    }

    private CalculationContext context(MeasurementSession session) {
        return new CalculationContext(
                footContext(session.getLeftCalibrationId(), session.getLeftSensorLayoutVersion()),
                footContext(session.getRightCalibrationId(), session.getRightSensorLayoutVersion()),
                analysisProperties.adcMaxFor(session.getAdcMax()));
    }

    private FootContext footContext(UUID calibrationId, String layoutVersion) {
        CalibrationProfile calibration = calibrations.findById(calibrationId)
                .orElseThrow(() -> new IllegalStateException("Session calibration is missing"));
        SensorLayout layout = layouts.findById(layoutVersion)
                .orElseThrow(() -> new IllegalStateException("Session layout is missing"));
        try {
            List<Double> baselines = objectMapper.readValue(calibration.getBaselineValuesJson(),
                    new TypeReference<List<Double>>() { });
            List<Double> scales = objectMapper.readValue(calibration.getScaleValuesJson(),
                    new TypeReference<List<Double>>() { });
            List<SensorPoint> points = objectMapper.readValue(layout.getPointsJson(),
                    new TypeReference<List<SensorPoint>>() { });
            return new FootContext(List.copyOf(baselines), List.copyOf(scales), List.copyOf(points));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored calibration or layout JSON is invalid", exception);
        }
    }

    private static double clamp(double value) { return Math.max(0, Math.min(100, value)); }
    private static double clamp01(double value) { return Math.max(0, Math.min(1, value)); }

    private static final class SnapshotState {
        private volatile FootRealtimeData left;
        private volatile FootRealtimeData right;

        synchronized void update(FootSide side, FootRealtimeData value) {
            FootRealtimeData previous = side == FootSide.LEFT ? left : right;
            if (previous != null && (value.deviceTimeMs() < previous.deviceTimeMs()
                    || value.deviceTimeMs() == previous.deviceTimeMs()
                    && value.lastSequence() <= previous.lastSequence())) {
                return;
            }
            if (side == FootSide.LEFT) left = value; else right = value;
        }
    }

    private record CalculationContext(FootContext left, FootContext right, int adcMax) { }
    private record FootContext(List<Double> baselines, List<Double> scales, List<SensorPoint> points) { }
}
