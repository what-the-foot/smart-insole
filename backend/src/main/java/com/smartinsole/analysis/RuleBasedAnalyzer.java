package com.smartinsole.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.AnalysisDtos.CenterOfPressure;
import com.smartinsole.analysis.AnalysisDtos.ComputedAnalysis;
import com.smartinsole.analysis.AnalysisDtos.ComputedPattern;
import com.smartinsole.analysis.AnalysisDtos.ObservationSummaryItem;
import com.smartinsole.analysis.AnalysisDtos.PressureDistribution;
import com.smartinsole.calibration.CalibrationProfile;
import com.smartinsole.calibration.CalibrationProfileRepository;
import com.smartinsole.device.DeviceDtos.SensorPoint;
import com.smartinsole.device.SensorLayout;
import com.smartinsole.device.SensorLayoutRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.ObservationLevel;
import com.smartinsole.global.common.DomainTypes.PatternSeverity;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.measurement.MeasurementQualityStats;
import com.smartinsole.measurement.MeasurementSession;
import com.smartinsole.measurement.PressureFrameRepository.StoredPressureFrame;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * rule-v1.2.0: quality -> calibration -> smoothing -> contact windows (valid steps) -> per-window
 * features -> observation level per pattern code -> recommendations. Every window is one contact
 * interval of one foot; LEFT_RIGHT_ASYMMETRY pairs left and right windows in receiver time order.
 */
@Component
public class RuleBasedAnalyzer {
    private static final Logger log = LoggerFactory.getLogger(RuleBasedAnalyzer.class);
    private static final String LOW_DATA_QUALITY_FLAG = "LOW_DATA_QUALITY";
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
        List<NormalizedFrame> leftFrames = bySide.getOrDefault(FootSide.LEFT, List.of());
        List<NormalizedFrame> rightFrames = bySide.getOrDefault(FootSide.RIGHT, List.of());

        QualitySummary quality = quality(storedQuality, bySide);
        List<ContactWindow> leftWindows = windows(leftFrames, session.getSampleRateHz());
        List<ContactWindow> rightWindows = windows(rightFrames, session.getSampleRateHz());
        ContactSummary leftContact = ContactSummary.of(leftWindows);
        ContactSummary rightContact = ContactSummary.of(rightWindows);
        int validStepCount = leftWindows.size() + rightWindows.size();
        double symmetry = symmetry(leftContact.averageDurationMs(), rightContact.averageDurationMs());
        double cadence = cadence(bySide, validStepCount);
        DistributionMetrics leftDistribution = distribution(leftFrames, contexts.get(FootSide.LEFT).points());
        DistributionMetrics rightDistribution = distribution(rightFrames, contexts.get(FootSide.RIGHT).points());
        PressureDistribution responseDistribution = pressureDistribution(leftDistribution, rightDistribution,
                sensorShare(leftFrames, leftWindows), sensorShare(rightFrames, rightWindows));

        List<ObservationSummaryItem> observationSummary = observe(
                new FootData(leftFrames, leftWindows, contexts.get(FootSide.LEFT).points()),
                new FootData(rightFrames, rightWindows, contexts.get(FootSide.RIGHT).points()));
        List<ComputedPattern> patterns = patterns(observationSummary);
        Set<String> recommendations = new LinkedHashSet<>();
        if (quality.score() < properties.poorQualityScoreThreshold()) {
            recommendations.add(PatternCatalog.REMEASURE_GUIDE);
        }
        for (ComputedPattern pattern : patterns) {
            recommendations.add(PatternCatalog.definition(pattern.code()).recommendationCode());
        }
        return new ComputedAnalysis(quality.score(), quality.level(), quality.missingRate(),
                List.copyOf(quality.flags()), cadence, leftContact.averageDurationMs(),
                rightContact.averageDurationMs(), symmetry, validStepCount, responseDistribution,
                List.copyOf(patterns), List.copyOf(observationSummary), List.copyOf(recommendations));
    }

    private static NormalizedFrame calibrate(StoredPressureFrame raw, FootContext context, int adcMax) {
        List<Double> values = new ArrayList<>(raw.sensorValues().size());
        for (int index = 0; index < raw.sensorValues().size(); index++) {
            values.add(normalize(raw.sensorValues().get(index), context.baselines().get(index),
                    context.scales().get(index), adcMax));
        }
        // Pairing key for left/right windows: per-frame receiver time (1.1) or the batch receipt time.
        Instant pairingTime = raw.receiverReceivedAt() != null ? raw.receiverReceivedAt() : raw.receivedAt();
        return new NormalizedFrame(raw.footSide(), raw.sequence(), raw.deviceTimeMs(), pairingTime,
                List.copyOf(values));
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
                NormalizedFrame source = frames.get(index);
                result.add(new NormalizedFrame(side, source.sequence(), source.deviceTimeMs(),
                        source.pairingTime(), List.copyOf(values)));
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
        // LOW_DATA_QUALITY is a quality flag (not a pattern) since rule-v1.2.0.
        if (score < properties.poorQualityScoreThreshold()) {
            flags.add(LOW_DATA_QUALITY_FLAG);
        }
        return new QualitySummary(score, level, missing, flags);
    }

    private List<ContactWindow> windows(List<NormalizedFrame> frames, int sampleRate) {
        int sensorCount = frames.isEmpty() ? 0 : frames.getFirst().values().size();
        return contactWindows(frames.stream().map(NormalizedFrame::deviceTimeMs).toList(),
                frames.stream().map(frame -> total(frame.values())).toList(),
                sampleRate, properties.contactThreshold(sensorCount));
    }

    /**
     * Splits one foot's frames (already in time order) into contact windows: runs of frames whose total
     * is at or above the contact threshold. A device-time gap longer than three sample periods closes
     * the current window so a window never spans a transmission hole.
     */
    static List<ContactWindow> contactWindows(List<Long> deviceTimes, List<Double> pressureTotals,
                                              int sampleRate, double contactThreshold) {
        if (deviceTimes.size() != pressureTotals.size()) {
            throw new IllegalArgumentException("Device times and pressure totals must have the same size");
        }
        List<ContactWindow> windows = new ArrayList<>();
        if (deviceTimes.isEmpty()) return windows;
        double samplePeriod = 1000.0 / sampleRate;
        double maximumContiguousDelta = samplePeriod * 3;
        Integer startIndex = null;
        Long start = null;
        Long previousTime = null;
        int previousIndex = -1;
        for (int index = 0; index < deviceTimes.size(); index++) {
            long deviceTime = deviceTimes.get(index);
            if (previousTime != null && deviceTime - previousTime > maximumContiguousDelta && start != null) {
                windows.add(window(startIndex, previousIndex, start, previousTime, samplePeriod));
                start = null;
                startIndex = null;
            }
            boolean contact = pressureTotals.get(index) >= contactThreshold;
            if (contact && start == null) {
                start = deviceTime;
                startIndex = index;
            }
            if (!contact && start != null) {
                windows.add(window(startIndex, previousIndex, start, previousTime, samplePeriod));
                start = null;
                startIndex = null;
            }
            previousTime = deviceTime;
            previousIndex = index;
        }
        if (start != null) windows.add(window(startIndex, previousIndex, start, previousTime, samplePeriod));
        return windows;
    }

    private static ContactWindow window(int startIndex, int endIndex, long start, long end, double samplePeriod) {
        return new ContactWindow(startIndex, endIndex, start, end, Math.max(samplePeriod, end - start + samplePeriod));
    }

    static ContactSummary contactsFromTotals(List<Long> deviceTimes, List<Double> pressureTotals,
                                             int sampleRate, double contactThreshold) {
        return ContactSummary.of(contactWindows(deviceTimes, pressureTotals, sampleRate, contactThreshold));
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
                distributionValues(rightFrames, rightPoints), null, null);
    }

    private static PressureDistribution pressureDistribution(DistributionMetrics left, DistributionMetrics right,
                                                             List<Double> leftShare, List<Double> rightShare) {
        return new PressureDistribution(left.medialRatio(), left.lateralRatio(),
                right.medialRatio(), right.lateralRatio(), left.heelRatio(), right.heelRatio(),
                left.midfootRatio(), right.midfootRatio(), left.forefootRatio(), right.forefootRatio(),
                left.peakPressure(), right.peakPressure(), left.meanCoP(), right.meanCoP(),
                leftShare, rightShare);
    }

    private static DistributionMetrics distribution(List<NormalizedFrame> frames, List<SensorPoint> points) {
        return distributionValues(frames.stream().map(NormalizedFrame::values).toList(), points);
    }

    private static DistributionMetrics distributionValues(List<List<Double>> frames, List<SensorPoint> points) {
        double total = 0, medial = 0, lateral = 0, heel = 0, midfoot = 0, forefoot = 0, hallux = 0;
        double peak = 0, weightedX = 0, weightedY = 0;
        boolean hasHalluxSensor = points.stream().anyMatch(RuleBasedAnalyzer::isHallux);
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
                if (isHallux(point)) hallux += value;
            }
        }
        double sideTotal = medial + lateral;
        return new DistributionMetrics(sideTotal == 0 ? 0 : medial / sideTotal,
                sideTotal == 0 ? 0 : lateral / sideTotal,
                total == 0 ? 0 : heel / total,
                total == 0 ? 0 : midfoot / total,
                total == 0 ? 0 : forefoot / total,
                peak,
                total == 0 ? null : new CenterOfPressure(weightedX / total, weightedY / total),
                !hasHalluxSensor || total == 0 ? null : hallux / total * 100.0);
    }

    /** The hallux (big toe) sensor: TOE region on the medial side (S08 in layout-s01s08-v1). */
    private static boolean isHallux(SensorPoint point) {
        return "TOE".equals(point.region()) && "MEDIAL".equals(point.medialLateral());
    }

    /**
     * Contact-frame mean of each sensor's share of the frame total (sensor / total x 100), in layout
     * index order and renormalised to sum to 100. Null when the foot has no contact frame.
     */
    static List<Double> sensorShare(List<NormalizedFrame> frames, List<ContactWindow> windows) {
        if (frames.isEmpty()) return null;
        int sensors = frames.getFirst().values().size();
        double[] sums = new double[sensors];
        int counted = 0;
        for (ContactWindow window : windows) {
            for (int index = window.startIndex(); index <= window.endIndex(); index++) {
                List<Double> values = frames.get(index).values();
                double total = total(values);
                if (total <= 0) continue;
                for (int sensor = 0; sensor < sensors; sensor++) {
                    sums[sensor] += values.get(sensor) / total * 100.0;
                }
                counted++;
            }
        }
        if (counted == 0) return null;
        double sum = 0;
        for (double value : sums) sum += value;
        List<Double> share = new ArrayList<>(sensors);
        for (double value : sums) share.add(sum == 0 ? 0.0 : value / sum * 100.0);
        return List.copyOf(share);
    }

    private static double total(List<Double> values) {
        double total = 0;
        for (double value : values) total += value;
        return total;
    }

    /**
     * Evaluates the six rule-v1.2.0 codes over the valid-step windows. Foot-level codes count every
     * window of both feet; LOW_HALLUX_SIGNAL only counts windows of feet whose layout has a hallux
     * sensor; LEFT_RIGHT_ASYMMETRY counts left/right window pairs in receiver time order.
     */
    private List<ObservationSummaryItem> observe(FootData left, FootData right) {
        List<DistributionMetrics> windowMetrics = new ArrayList<>();
        for (FootData foot : List.of(left, right)) {
            for (ContactWindow window : foot.windows()) {
                List<List<Double>> values = foot.frames().subList(window.startIndex(), window.endIndex() + 1)
                        .stream().map(NormalizedFrame::values).toList();
                windowMetrics.add(distributionValues(values, foot.points()));
            }
        }
        int windows = windowMetrics.size();
        List<DistributionMetrics> halluxWindows = windowMetrics.stream()
                .filter(metrics -> metrics.halluxSharePct() != null).toList();
        List<ContactWindow> leftOrdered = pairingOrder(left);
        List<ContactWindow> rightOrdered = pairingOrder(right);
        int pairs = Math.min(leftOrdered.size(), rightOrdered.size());
        int asymmetric = 0;
        for (int index = 0; index < pairs; index++) {
            if (symmetry(leftOrdered.get(index).durationMs(), rightOrdered.get(index).durationMs())
                    >= properties.asymmetryThresholdPercent()) {
                asymmetric++;
            }
        }
        List<ObservationSummaryItem> summary = new ArrayList<>(PatternCatalog.CODES.size());
        summary.add(item(PatternCatalog.MEDIAL_LOAD_TENDENCY,
                count(windowMetrics, metrics -> metrics.medialRatio() >= properties.medialRatioThreshold()), windows));
        summary.add(item(PatternCatalog.LATERAL_LOAD_TENDENCY,
                count(windowMetrics, metrics -> metrics.lateralRatio() >= properties.lateralRatioThreshold()), windows));
        summary.add(item(PatternCatalog.LEFT_RIGHT_ASYMMETRY, asymmetric, pairs));
        summary.add(item(PatternCatalog.LOW_HALLUX_SIGNAL,
                count(halluxWindows, metrics -> metrics.halluxSharePct() < properties.halluxSharePctThreshold()),
                halluxWindows.size()));
        summary.add(item(PatternCatalog.FOREFOOT_LOAD_TENDENCY,
                count(windowMetrics, metrics -> metrics.forefootRatio() >= properties.forefootRatioThreshold()), windows));
        summary.add(item(PatternCatalog.REARFOOT_LOAD_TENDENCY,
                count(windowMetrics, metrics -> metrics.heelRatio() >= properties.rearfootRatioThreshold()), windows));
        return summary;
    }

    private static List<ContactWindow> pairingOrder(FootData foot) {
        return foot.windows().stream()
                .sorted(Comparator.comparing((ContactWindow window) -> foot.frames().get(window.startIndex()).pairingTime())
                        .thenComparingLong(ContactWindow::startDeviceTimeMs))
                .toList();
    }

    private static int count(List<DistributionMetrics> metrics, java.util.function.Predicate<DistributionMetrics> test) {
        return (int) metrics.stream().filter(test).count();
    }

    private ObservationSummaryItem item(String code, int observed, int windows) {
        double rate = windows == 0 ? 0.0 : (double) observed / windows;
        return new ObservationSummaryItem(code, level(rate, windows), rate, observed, windows);
    }

    ObservationLevel level(double rate, int windows) {
        if (windows < properties.minObservationWindows()) return ObservationLevel.NOT_OBSERVED;
        if (rate >= properties.repeatedObservationRate()) return ObservationLevel.REPEATEDLY_OBSERVED;
        if (rate >= properties.partialObservationRate()) return ObservationLevel.PARTIALLY_OBSERVED;
        return ObservationLevel.NOT_OBSERVED;
    }

    /** Patterns are the PARTIALLY/REPEATEDLY observed codes, strongest first. */
    private static List<ComputedPattern> patterns(List<ObservationSummaryItem> summary) {
        return summary.stream()
                .filter(item -> item.observationLevel() != ObservationLevel.NOT_OBSERVED)
                .sorted(Comparator.comparing(ObservationSummaryItem::observationLevel).reversed()
                        .thenComparing(Comparator.comparingDouble(ObservationSummaryItem::occurrenceRate).reversed())
                        .thenComparingInt(item -> PatternCatalog.CODES.indexOf(item.code())))
                .map(RuleBasedAnalyzer::pattern)
                .toList();
    }

    private static ComputedPattern pattern(ObservationSummaryItem item) {
        PatternCatalog.Definition definition = PatternCatalog.definition(item.code());
        PatternSeverity severity = item.observationLevel() == ObservationLevel.REPEATEDLY_OBSERVED
                ? PatternSeverity.CAUTION : PatternSeverity.INFO;
        String evidence = String.format(Locale.ROOT, "%s %d회 중 %d회(%.0f%%)에서 기능 검증용 기준을 넘었습니다.",
                definition.windowNoun(), item.windowCount(), item.observedCount(), item.occurrenceRate() * 100.0);
        return new ComputedPattern(item.code(), severity, definition.title(), definition.message(), evidence,
                item.observationLevel(), item.occurrenceRate(), item.observedCount(), item.windowCount());
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

    record NormalizedFrame(FootSide footSide, long sequence, long deviceTimeMs, Instant pairingTime,
                           List<Double> values) { }
    private record FootContext(List<Double> baselines, List<Double> scales, List<SensorPoint> points) { }
    private record FootData(List<NormalizedFrame> frames, List<ContactWindow> windows, List<SensorPoint> points) { }
    /** One contact interval (valid step) of one foot: frame index range plus duration. */
    record ContactWindow(int startIndex, int endIndex, long startDeviceTimeMs, long endDeviceTimeMs,
                         double durationMs) { }
    record ContactSummary(int count, double averageDurationMs) {
        static ContactSummary of(List<ContactWindow> windows) {
            return new ContactSummary(windows.size(), windows.stream()
                    .mapToDouble(ContactWindow::durationMs).average().orElse(0));
        }
    }
    private record DistributionMetrics(double medialRatio, double lateralRatio,
                                       double heelRatio, double midfootRatio, double forefootRatio,
                                       double peakPressure, CenterOfPressure meanCoP, Double halluxSharePct) { }
    record QualitySummary(int score, QualityLevel level, double missingRate, Set<String> flags) { }
}
