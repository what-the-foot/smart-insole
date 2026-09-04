package com.smartinsole.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.AnalysisDtos.ComputedAnalysis;
import com.smartinsole.analysis.AnalysisDtos.ComputedPattern;
import com.smartinsole.analysis.AnalysisDtos.CenterOfPressure;
import com.smartinsole.analysis.AnalysisDtos.PressureDistribution;
import com.smartinsole.calibration.CalibrationProfile;
import com.smartinsole.calibration.CalibrationProfileRepository;
import com.smartinsole.device.DeviceDtos.SensorPoint;
import com.smartinsole.device.SensorLayout;
import com.smartinsole.device.SensorLayoutRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.PatternSeverity;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.measurement.MeasurementQualityStats;
import com.smartinsole.measurement.MeasurementSession;
import com.smartinsole.measurement.PressureFrameRepository.StoredPressureFrame;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class RuleBasedAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(RuleBasedAnalyzer.class);
    private final CalibrationProfileRepository calibrations;
    private final SensorLayoutRepository layouts;
    private final ObjectMapper objectMapper;
    private final AnalysisProperties properties;

    public RuleBasedAnalyzer(CalibrationProfileRepository calibrations, SensorLayoutRepository layouts,
                             ObjectMapper objectMapper, AnalysisProperties properties) {
        this.calibrations = calibrations;
        this.layouts = layouts;
        this.objectMapper = objectMapper;
        this.properties = properties;
        if (properties.defaultsAreFunctionalTestValues()) {
            log.info("Analysis thresholds for {} are functional-test defaults, not clinically validated values",
                    properties.algorithmVersion());
        }
    }

    public ComputedAnalysis analyze(MeasurementSession session, List<StoredPressureFrame> rawFrames,
                                    MeasurementQualityStats storedQuality) {
        Map<FootSide, FootContext> contexts = new EnumMap<>(FootSide.class);
        contexts.put(FootSide.LEFT, context(session.getLeftCalibrationId(), session.getLeftSensorLayoutVersion()));
        contexts.put(FootSide.RIGHT, context(session.getRightCalibrationId(), session.getRightSensorLayoutVersion()));

        int adcMax = properties.adcMaxFor(session.getAdcMax());
        List<NormalizedFrame> calibrated = rawFrames.stream()
                .sorted(Comparator.comparingLong(StoredPressureFrame::deviceTimeMs)
                        .thenComparingLong(StoredPressureFrame::sequence))
                .map(frame -> calibrate(frame, contexts.get(frame.footSide()), adcMax))
                .toList();
        List<NormalizedFrame> filtered = movingAverage(calibrated);
        Map<FootSide, List<NormalizedFrame>> bySide = filtered.stream()
                .collect(java.util.stream.Collectors.groupingBy(NormalizedFrame::footSide,
                        () -> new EnumMap<>(FootSide.class), java.util.stream.Collectors.toList()));

        QualitySummary quality = quality(storedQuality, bySide);
        ContactSummary leftContact = contacts(bySide.getOrDefault(FootSide.LEFT, List.of()), session.getSampleRateHz());
        ContactSummary rightContact = contacts(bySide.getOrDefault(FootSide.RIGHT, List.of()), session.getSampleRateHz());
        int validStepCount = leftContact.count() + rightContact.count();
        double symmetry = symmetry(leftContact.averageDurationMs(), rightContact.averageDurationMs());
        double cadence = cadence(bySide, validStepCount);
        DistributionMetrics leftDistribution = distribution(bySide.getOrDefault(FootSide.LEFT, List.of()),
                contexts.get(FootSide.LEFT).points());
        DistributionMetrics rightDistribution = distribution(bySide.getOrDefault(FootSide.RIGHT, List.of()),
                contexts.get(FootSide.RIGHT).points());
        PressureDistribution responseDistribution = pressureDistribution(leftDistribution, rightDistribution);

        List<ComputedPattern> patterns = patterns(quality, leftContact, rightContact, symmetry,
                leftDistribution, rightDistribution);
        Set<String> recommendations = new LinkedHashSet<>();
        for (ComputedPattern pattern : patterns) {
            switch (pattern.code()) {
                case "LOW_DATA_QUALITY" -> recommendations.add("REMEASURE_GUIDE");
                case "LEFT_RIGHT_ASYMMETRY", "SHORT_CONTACT_TIME" ->
                        recommendations.add("ANKLE_STABILITY_BASIC");
                case "MEDIAL_LOAD_TENDENCY", "LATERAL_LOAD_TENDENCY", "HIGH_MIDFOOT_LOAD" ->
                        recommendations.add("BALANCED_FOOT_LOADING");
                default -> { }
            }
        }
        return new ComputedAnalysis(quality.score(), quality.level(), quality.missingRate(),
                List.copyOf(quality.flags()), cadence, leftContact.averageDurationMs(),
                rightContact.averageDurationMs(), symmetry, validStepCount, responseDistribution,
                leftDistribution.midfootRatio(), rightDistribution.midfootRatio(),
                List.copyOf(patterns), List.copyOf(recommendations));
    }

    private static NormalizedFrame calibrate(StoredPressureFrame raw, FootContext context, int adcMax) {
        List<Double> values = new ArrayList<>(raw.sensorValues().size());
        for (int index = 0; index < raw.sensorValues().size(); index++) {
            values.add(normalize(raw.sensorValues().get(index), context.baselines().get(index),
                    context.scales().get(index), adcMax));
        }
        return new NormalizedFrame(raw.footSide(), raw.sequence(), raw.deviceTimeMs(), List.copyOf(values));
    }

    private static List<NormalizedFrame> movingAverage(List<NormalizedFrame> input) {
        Map<FootSide, List<NormalizedFrame>> bySide = input.stream()
                .collect(java.util.stream.Collectors.groupingBy(NormalizedFrame::footSide,
                        () -> new EnumMap<>(FootSide.class), java.util.stream.Collectors.toList()));
        List<NormalizedFrame> result = new ArrayList<>(input.size());
        for (FootSide side : FootSide.values()) {
            List<NormalizedFrame> frames = bySide.getOrDefault(side, List.of()).stream()
                    .sorted(Comparator.comparingLong(NormalizedFrame::deviceTimeMs)
                            .thenComparingLong(NormalizedFrame::sequence)).toList();
            for (int index = 0; index < frames.size(); index++) {
                int from = Math.max(0, index - 1);
                int to = Math.min(frames.size() - 1, index + 1);
                List<Double> values = new ArrayList<>(frames.get(index).values().size());
                for (int sensor = 0; sensor < frames.get(index).values().size(); sensor++) {
                    double sum = 0;
                    for (int sample = from; sample <= to; sample++) sum += frames.get(sample).values().get(sensor);
                    values.add(sum / (to - from + 1));
                }
                result.add(new NormalizedFrame(side, frames.get(index).sequence(),
                        frames.get(index).deviceTimeMs(), List.copyOf(values)));
            }
        }
        return result.stream().sorted(Comparator.comparingLong(NormalizedFrame::deviceTimeMs)
                .thenComparingLong(NormalizedFrame::sequence)).toList();
    }

    private QualitySummary quality(MeasurementQualityStats stats,
                                   Map<FootSide, List<NormalizedFrame>> bySide) {
        boolean leftMissing = bySide.getOrDefault(FootSide.LEFT, List.of()).isEmpty();
        boolean rightMissing = bySide.getOrDefault(FootSide.RIGHT, List.of()).isEmpty();
        int frameCount = bySide.values().stream().mapToInt(List::size).sum();
        return qualityForAvailability(stats, leftMissing, rightMissing, frameCount);
    }

    QualitySummary qualityForAvailability(MeasurementQualityStats stats, boolean leftMissing,
                                          boolean rightMissing, int frameCount) {
        int score = stats == null ? 100 : stats.getScore();
        double missing = stats == null ? 0 : stats.getMissingFrameRate();
        Set<String> flags = stats == null ? new LinkedHashSet<>() : stats.flags(objectMapper);
        if (leftMissing) {
            flags.add("LEFT_DATA_MISSING");
            if (stats == null) score -= 35;
        }
        if (rightMissing) {
            flags.add("RIGHT_DATA_MISSING");
            if (stats == null) score -= 35;
        }
        if (frameCount == 0) {
            flags.add("INSUFFICIENT_DATA");
            if (stats == null) score = 0;
        }
        score = Math.max(0, Math.min(100, score));
        QualityLevel level = stats == null
                ? score >= 85 ? QualityLevel.GOOD
                    : score >= properties.poorQualityScoreThreshold() ? QualityLevel.ACCEPTABLE : QualityLevel.POOR
                : stats.getLevel();
        return new QualitySummary(score, level, missing, flags);
    }

    private ContactSummary contacts(List<NormalizedFrame> frames, int sampleRate) {
        int sensorCount = frames.isEmpty() ? 0 : frames.getFirst().values().size();
        return contactsFromTotals(frames.stream().map(NormalizedFrame::deviceTimeMs).toList(),
                frames.stream().map(frame -> frame.values().stream().mapToDouble(Double::doubleValue).sum()).toList(),
                sampleRate, properties.contactThreshold(sensorCount));
    }

    static ContactSummary contactsFromTotals(List<Long> deviceTimes, List<Double> pressureTotals,
                                             int sampleRate, double contactThreshold) {
        if (deviceTimes.size() != pressureTotals.size()) {
            throw new IllegalArgumentException("Device times and pressure totals must have the same size");
        }
        if (deviceTimes.isEmpty()) return new ContactSummary(0, 0);
        List<Double> durations = new ArrayList<>();
        Long start = null;
        Long previousTime = null;
        double samplePeriod = 1000.0 / sampleRate;
        double maximumContiguousDelta = samplePeriod * 3;
        for (int index = 0; index < deviceTimes.size(); index++) {
            long deviceTime = deviceTimes.get(index);
            if (previousTime != null && deviceTime - previousTime > maximumContiguousDelta && start != null) {
                durations.add(Math.max(samplePeriod, previousTime - start + samplePeriod));
                start = null;
            }
            boolean contact = pressureTotals.get(index) >= contactThreshold;
            if (contact && start == null) start = deviceTime;
            if (!contact && start != null) {
                durations.add(Math.max(samplePeriod, previousTime - start + samplePeriod));
                start = null;
            }
            previousTime = deviceTime;
        }
        if (start != null) durations.add(Math.max(samplePeriod, previousTime - start + samplePeriod));
        return new ContactSummary(durations.size(), durations.stream().mapToDouble(Double::doubleValue)
                .average().orElse(0));
    }

    private static double cadence(Map<FootSide, List<NormalizedFrame>> bySide, int contacts) {
        List<Long> leftTimes = bySide.getOrDefault(FootSide.LEFT, List.of()).stream()
                .map(NormalizedFrame::deviceTimeMs).toList();
        List<Long> rightTimes = bySide.getOrDefault(FootSide.RIGHT, List.of()).stream()
                .map(NormalizedFrame::deviceTimeMs).toList();
        return cadenceFromDeviceTimes(leftTimes, rightTimes, contacts);
    }

    static double cadenceFromDeviceTimes(List<Long> leftTimes, List<Long> rightTimes, int contacts) {
        if (contacts == 0) return 0;
        long observationMs = Math.max(timelineDuration(leftTimes), timelineDuration(rightTimes));
        return observationMs <= 0 ? 0 : contacts * 60_000.0 / observationMs;
    }

    private static long timelineDuration(List<Long> times) {
        if (times.size() < 2) return 0;
        long min = times.stream().mapToLong(Long::longValue).min().orElse(0);
        long max = times.stream().mapToLong(Long::longValue).max().orElse(min);
        return Math.max(0, max - min);
    }

    static double symmetry(double left, double right) {
        double mean = (left + right) / 2.0;
        return mean <= 0 ? 0 : Math.abs(left - right) / mean * 100.0;
    }

    /** Calibrates one raw ADC value and maps it onto the 0..100 scale of the session's ADC ceiling. */
    static double normalize(double raw, double baseline, double scale, int adcMax) {
        double calibrated = Math.max(0, (raw - baseline) * scale);
        return Math.max(0, Math.min(100, calibrated * 100.0 / adcMax));
    }

    static PressureDistribution pressureDistributionFromValues(
            List<List<Double>> leftFrames, List<SensorPoint> leftPoints,
            List<List<Double>> rightFrames, List<SensorPoint> rightPoints) {
        return pressureDistribution(distributionValues(leftFrames, leftPoints),
                distributionValues(rightFrames, rightPoints));
    }

    private static PressureDistribution pressureDistribution(DistributionMetrics left,
                                                             DistributionMetrics right) {
        return new PressureDistribution(left.medialRatio(), left.lateralRatio(),
                right.medialRatio(), right.lateralRatio(), left.heelRatio(), right.heelRatio(),
                left.midfootRatio(), right.midfootRatio(), left.forefootRatio(), right.forefootRatio(),
                left.peakPressure(), right.peakPressure(), left.meanCoP(), right.meanCoP());
    }

    private static DistributionMetrics distribution(List<NormalizedFrame> frames, List<SensorPoint> points) {
        return distributionValues(frames.stream().map(NormalizedFrame::values).toList(), points);
    }

    private static DistributionMetrics distributionValues(List<List<Double>> frames, List<SensorPoint> points) {
        double total = 0, medial = 0, lateral = 0, heel = 0, midfoot = 0, forefoot = 0;
        double peak = 0, weightedX = 0, weightedY = 0;
        for (List<Double> frame : frames) {
            for (int index = 0; index < frame.size(); index++) {
                double value = frame.get(index);
                SensorPoint point = points.get(index);
                total += value;
                peak = Math.max(peak, value);
                weightedX += value * point.x();
                weightedY += value * point.y();
                if ("MEDIAL".equals(point.medialLateral())) medial += value;
                if ("LATERAL".equals(point.medialLateral())) lateral += value;
                if ("HEEL".equals(point.region())) heel += value;
                if ("MIDFOOT".equals(point.region())) midfoot += value;
                if ("FOREFOOT".equals(point.region()) || "TOE".equals(point.region())) forefoot += value;
            }
        }
        double sideTotal = medial + lateral;
        return new DistributionMetrics(sideTotal == 0 ? 0 : medial / sideTotal,
                sideTotal == 0 ? 0 : lateral / sideTotal,
                total == 0 ? 0 : heel / total,
                total == 0 ? 0 : midfoot / total,
                total == 0 ? 0 : forefoot / total,
                peak,
                total == 0 ? null : new CenterOfPressure(weightedX / total, weightedY / total));
    }

    private List<ComputedPattern> patterns(QualitySummary quality, ContactSummary left,
                                           ContactSummary right, double symmetry,
                                           DistributionMetrics leftDistribution,
                                           DistributionMetrics rightDistribution) {
        List<ComputedPattern> patterns = new ArrayList<>();
        if (quality.score() < properties.poorQualityScoreThreshold()) {
            patterns.add(new ComputedPattern("LOW_DATA_QUALITY", PatternSeverity.RECHECK,
                    "데이터 품질 확인 필요", "데이터 품질이 낮아 같은 조건에서 재측정을 권장합니다.",
                    "품질 점수는 " + quality.score() + "점이며 기능 검증용 기준보다 낮았습니다."));
        }
        if (left.averageDurationMs() > 0 && right.averageDurationMs() > 0
                && symmetry >= properties.asymmetryThresholdPercent()) {
            patterns.add(new ComputedPattern("LEFT_RIGHT_ASYMMETRY", PatternSeverity.CAUTION,
                    "좌우 접촉 시간 차이", "왼발과 오른발의 접촉 시간 차이가 관찰되었습니다.",
                    String.format(java.util.Locale.ROOT, "기능 검증용 좌우 지수는 %.1f%%입니다.", symmetry)));
        }
        if (Math.max(leftDistribution.medialRatio(), rightDistribution.medialRatio())
                >= properties.medialRatioThreshold()) {
            patterns.add(new ComputedPattern("MEDIAL_LOAD_TENDENCY", PatternSeverity.INFO,
                    "내측 압력 집중 경향", "발 안쪽 압력이 상대적으로 집중되는 경향이 관찰되었습니다.",
                    "센서 영역별 상대 압력 비율을 기능 검증용 기준과 비교했습니다."));
        }
        if (Math.max(leftDistribution.lateralRatio(), rightDistribution.lateralRatio())
                >= properties.lateralRatioThreshold()) {
            patterns.add(new ComputedPattern("LATERAL_LOAD_TENDENCY", PatternSeverity.INFO,
                    "외측 압력 집중 경향", "발 바깥쪽 압력이 상대적으로 집중되는 경향이 관찰되었습니다.",
                    "센서 영역별 상대 압력 비율을 기능 검증용 기준과 비교했습니다."));
        }
        if (Math.max(leftDistribution.midfootRatio(), rightDistribution.midfootRatio())
                >= properties.midfootRatioThreshold()) {
            patterns.add(new ComputedPattern("HIGH_MIDFOOT_LOAD", PatternSeverity.INFO,
                    "중족부 압력 비율", "중족부 압력 비율이 상대적으로 높게 관찰되었습니다.",
                    "센서 배치의 MIDFOOT 영역 비율을 기능 검증용 기준과 비교했습니다."));
        }
        boolean shortContact = left.averageDurationMs() > 0
                && left.averageDurationMs() < properties.shortContactTimeMs()
                || right.averageDurationMs() > 0 && right.averageDurationMs() < properties.shortContactTimeMs();
        if (shortContact) {
            patterns.add(new ComputedPattern("SHORT_CONTACT_TIME", PatternSeverity.INFO,
                    "짧은 접촉 시간 경향", "일부 발의 평균 접촉 시간이 짧게 관찰되었습니다.",
                    "평균 접촉 시간을 기능 검증용 기준과 비교했습니다."));
        }
        return patterns;
    }

    private FootContext context(java.util.UUID calibrationId, String layoutVersion) {
        CalibrationProfile calibration = calibrations.findById(calibrationId).orElseThrow();
        SensorLayout layout = layouts.findById(layoutVersion).orElseThrow();
        try {
            return new FootContext(
                    objectMapper.readValue(calibration.getBaselineValuesJson(), new TypeReference<List<Double>>() { }),
                    objectMapper.readValue(calibration.getScaleValuesJson(), new TypeReference<List<Double>>() { }),
                    objectMapper.readValue(layout.getPointsJson(), new TypeReference<List<SensorPoint>>() { }));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored calibration or layout JSON is invalid", exception);
        }
    }

    private record NormalizedFrame(FootSide footSide, long sequence, long deviceTimeMs, List<Double> values) { }
    private record FootContext(List<Double> baselines, List<Double> scales, List<SensorPoint> points) { }
    record ContactSummary(int count, double averageDurationMs) { }
    private record DistributionMetrics(double medialRatio, double lateralRatio,
                                       double heelRatio, double midfootRatio, double forefootRatio,
                                       double peakPressure, CenterOfPressure meanCoP) { }
    record QualitySummary(int score, QualityLevel level, double missingRate, Set<String> flags) { }
}
