package com.smartinsole.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import static org.mockito.Mockito.when;

import com.smartinsole.analysis.dto.AnalysisDtos.ComputedAnalysis;
import com.smartinsole.analysis.dto.AnalysisDtos.ComputedPattern;
import com.smartinsole.analysis.dto.AnalysisDtos.ObservationSummaryItem;
import com.smartinsole.analysis.dto.AnalysisDtos.PressureDistribution;
import com.smartinsole.calibration.domain.CalibrationProfile;
import com.smartinsole.calibration.repository.CalibrationProfileRepository;
import com.smartinsole.device.dto.DeviceDtos.SensorPoint;
import com.smartinsole.device.domain.SensorLayout;
import com.smartinsole.device.repository.SensorLayoutRepository;
import com.smartinsole.global.common.DomainTypes.DataMode;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.ObservationLevel;
import com.smartinsole.global.common.DomainTypes.PatternSeverity;
import com.smartinsole.global.common.DomainTypes.SourceType;
import com.smartinsole.measurement.domain.MeasurementQualityStats;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.repository.PressureFrameRepository.StoredPressureFrame;
import com.smartinsole.support.TestSessions;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.smartinsole.support.TestAnalysisProperties;

class RuleBasedAnalyzerTest {
    @Test
    void symmetryAvoidsDivisionByZero() {
        assertThat(RuleBasedAnalyzer.symmetry(0, 0)).isZero();
    }

    @Test
    void symmetryUsesMeanOfBothFeet() {
        assertThat(RuleBasedAnalyzer.symmetry(600, 400)).isEqualTo(40.0);
    }

    @Test
    void cadenceUsesPerFootDurationsInsteadOfComparingIndependentClockOrigins() {
        assertThat(RuleBasedAnalyzer.cadenceFromDeviceTimes(
                List.of(100_000L, 101_000L), List.of(0L, 1_000L), 4)).isEqualTo(240.0);
    }

    @Test
    void contactDurationDoesNotSpanADeviceTimeGap() {
        RuleBasedAnalyzer.ContactSummary summary = RuleBasedAnalyzer.contactsFromTotals(
                List.of(0L, 10L, 10_000L, 10_010L), List.of(30.0, 30.0, 30.0, 0.0),
                100, 20.0);

        assertThat(summary.count()).isEqualTo(2);
        assertThat(summary.averageDurationMs()).isEqualTo(15.0);
    }

    @Test
    void zeroPressureAndMissingFootProduceZeroMetricsAndNullCop() {
        List<SensorPoint> points = points();
        PressureDistribution distribution = RuleBasedAnalyzer.pressureDistributionFromValues(
                List.of(List.of(0.0, 0.0, 0.0, 0.0)), points, List.of(), points);

        assertThat(distribution.leftMedialRatio()).isZero();
        assertThat(distribution.leftLateralRatio()).isZero();
        assertThat(distribution.leftHeelRatio()).isZero();
        assertThat(distribution.leftMidfootRatio()).isZero();
        assertThat(distribution.leftForefootRatio()).isZero();
        assertThat(distribution.leftPeakPressure()).isZero();
        assertThat(distribution.leftMeanCoP()).isNull();
        assertThat(distribution.rightPeakPressure()).isZero();
        assertThat(distribution.rightMeanCoP()).isNull();
    }

    @Test
    void distributionIncludesToeInForefootAndUsesPressureWeightedCop() {
        List<SensorPoint> points = points();
        List<List<Double>> frames = List.of(List.of(10.0, 20.0, 30.0, 100.0));

        PressureDistribution distribution = RuleBasedAnalyzer.pressureDistributionFromValues(
                frames, points, List.of(), points);

        assertThat(distribution.leftMedialRatio()).isCloseTo(120.0 / 150.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(distribution.leftLateralRatio()).isCloseTo(30.0 / 150.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(distribution.leftHeelRatio()).isCloseTo(10.0 / 160.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(distribution.leftMidfootRatio()).isCloseTo(20.0 / 160.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(distribution.leftForefootRatio()).isCloseTo(130.0 / 160.0,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(distribution.leftPeakPressure()).isEqualTo(100.0);
        assertThat(distribution.leftMeanCoP().x()).isCloseTo(0.484375,
                org.assertj.core.data.Offset.offset(1e-12));
        assertThat(distribution.leftMeanCoP().y()).isCloseTo(0.19375,
                org.assertj.core.data.Offset.offset(1e-12));
    }

    @Test
    void normalizationClampsBoundariesAndDistributionIsDeterministic() {
        assertThat(RuleBasedAnalyzer.normalize(0, 1, 1, 4095)).isZero();
        assertThat(RuleBasedAnalyzer.normalize(4095, 0, 1, 4095)).isEqualTo(100.0);
        assertThat(RuleBasedAnalyzer.normalize(4095, 0, 2, 4095)).isEqualTo(100.0);
        assertThat(RuleBasedAnalyzer.normalize(819, 0, 1, 4095)).isCloseTo(20.0,
                org.assertj.core.data.Offset.offset(1e-9));
        // The session scale, not a fixed constant, defines 100 %: the same raw value is 25 % on a 14-bit scale.
        assertThat(RuleBasedAnalyzer.normalize(4095, 0, 1, 16380)).isCloseTo(25.0,
                org.assertj.core.data.Offset.offset(1e-9));

        List<SensorPoint> points = points();
        List<List<Double>> left = List.of(List.of(10.0, 20.0, 30.0, 40.0),
                List.of(40.0, 30.0, 20.0, 10.0));
        List<List<Double>> right = List.of(List.of(5.0, 15.0, 25.0, 35.0));
        PressureDistribution first = RuleBasedAnalyzer.pressureDistributionFromValues(left, points, right, points);
        PressureDistribution second = RuleBasedAnalyzer.pressureDistributionFromValues(left, points, right, points);

        assertThat(second).isEqualTo(first);
    }

    @Test
    void finalizedOneSidedQualityScoreIsNotPenalizedTwiceByTheAnalyzer() {
        ObjectMapper mapper = new ObjectMapper();
        MeasurementQualityStats stats = MeasurementQualityStats.create(UUID.randomUUID(), Instant.now());
        stats.apply(10, 0, 0, new MeasurementQualityStats.SideCursor(1, 10, 100), null, Set.of(), mapper,
                Instant.now());
        stats.finalizeForSession(20, 0, Set.of("RIGHT_DATA_MISSING"), mapper, Instant.now());
        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer(mock(CalibrationProfileRepository.class),
                mock(SensorLayoutRepository.class), mapper,
                TestAnalysisProperties.defaults());

        RuleBasedAnalyzer.QualitySummary result = analyzer.qualityForAvailability(stats, false, true, 10);

        assertThat(result.score()).isEqualTo(stats.getScore());
        assertThat(result.level()).isEqualTo(stats.getLevel());
        assertThat(result.flags()).contains("RIGHT_DATA_MISSING");
    }

    @Test
    void observationLevelFollowsTheOccurrenceRateAndMinimumWindowCount() {
        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer(mock(CalibrationProfileRepository.class),
                mock(SensorLayoutRepository.class), new ObjectMapper(), TestAnalysisProperties.defaults());

        assertThat(analyzer.level(1.0, 3)).isEqualTo(ObservationLevel.NOT_OBSERVED);
        assertThat(analyzer.level(0.19, 4)).isEqualTo(ObservationLevel.NOT_OBSERVED);
        assertThat(analyzer.level(0.20, 4)).isEqualTo(ObservationLevel.PARTIALLY_OBSERVED);
        assertThat(analyzer.level(0.59, 10)).isEqualTo(ObservationLevel.PARTIALLY_OBSERVED);
        assertThat(analyzer.level(0.60, 10)).isEqualTo(ObservationLevel.REPEATEDLY_OBSERVED);
    }

    @Test
    void contactWindowsExposeFrameIndexRangesAndSplitOnTimeGaps() {
        List<RuleBasedAnalyzer.ContactWindow> windows = RuleBasedAnalyzer.contactWindows(
                List.of(0L, 10L, 20L, 30L, 40L, 50L, 1_000L, 1_010L),
                List.of(40.0, 40.0, 0.0, 0.0, 40.0, 40.0, 40.0, 40.0), 100, 30.0);

        assertThat(windows).extracting(RuleBasedAnalyzer.ContactWindow::startIndex).containsExactly(0, 4, 6);
        assertThat(windows).extracting(RuleBasedAnalyzer.ContactWindow::endIndex).containsExactly(1, 5, 7);
        assertThat(windows).extracting(RuleBasedAnalyzer.ContactWindow::durationMs).containsExactly(20.0, 20.0, 20.0);
    }

    @Test
    void contactWindowsCloseWhenDeviceTimeGoesBackwards() {
        // A receiver RESET restarts deviceTimeMs inside a contact run (frames stay in sequence order): the run
        // is split at the restart so no window spans the device restart.
        List<RuleBasedAnalyzer.ContactWindow> windows = RuleBasedAnalyzer.contactWindows(
                List.of(100L, 110L, 120L, 0L, 10L, 20L),
                List.of(40.0, 40.0, 40.0, 40.0, 40.0, 40.0), 100, 30.0);

        assertThat(windows).extracting(RuleBasedAnalyzer.ContactWindow::startIndex).containsExactly(0, 3);
        assertThat(windows).extracting(RuleBasedAnalyzer.ContactWindow::endIndex).containsExactly(2, 5);
        assertThat(windows).extracting(RuleBasedAnalyzer.ContactWindow::durationMs).containsExactly(30.0, 30.0);
    }

    @Test
    void sensorShareAveragesContactFramesInLayoutOrderAndSumsTo100() {
        List<RuleBasedAnalyzer.NormalizedFrame> frames = List.of(
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.LEFT, 1, 0, Instant.EPOCH, List.of(10.0, 30.0, 60.0, 0.0)),
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.LEFT, 2, 10, Instant.EPOCH, List.of(50.0, 50.0, 0.0, 0.0)),
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.LEFT, 3, 20, Instant.EPOCH, List.of(0.0, 0.0, 0.0, 0.0)));
        List<RuleBasedAnalyzer.ContactWindow> windows = List.of(new RuleBasedAnalyzer.ContactWindow(0, 1, 0, 10, 20));

        List<Double> share = RuleBasedAnalyzer.sensorShare(frames, windows);

        assertThat(share).hasSize(4);
        assertThat(share.get(0)).isCloseTo(30.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(share.get(1)).isCloseTo(40.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(share.get(2)).isCloseTo(30.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(share.stream().mapToDouble(Double::doubleValue).sum()).isCloseTo(100.0,
                org.assertj.core.data.Offset.offset(1e-9));
        assertThat(RuleBasedAnalyzer.sensorShare(frames, List.of())).isNull();
    }

    @Test
    void analyzeObservesTheSixCodesPerValidStepAndPairsFeetForAsymmetry() {
        Instant now = Instant.parse("2026-09-04T01:00:00Z");
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        CalibrationProfile leftCalibration = CalibrationProfile.identity(leftDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", now);
        CalibrationProfile rightCalibration = CalibrationProfile.identity(rightDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", now);
        CalibrationProfileRepository calibrations = mock(CalibrationProfileRepository.class);
        SensorLayoutRepository layouts = mock(SensorLayoutRepository.class);
        when(calibrations.findById(leftCalibration.getId())).thenReturn(Optional.of(leftCalibration));
        when(calibrations.findById(rightCalibration.getId())).thenReturn(Optional.of(rightCalibration));
        when(layouts.findById("layout-s01s08-v1")).thenReturn(Optional.of(new SensorLayout("layout-s01s08-v1", 8,
                s01s08Points(), true, now)));
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), leftDevice, rightDevice,
                leftCalibration.getId(), rightCalibration.getId(), "layout-s01s08-v1", "layout-s01s08-v1", 50,
                SourceType.DEVICE, 4095, null, now.minusSeconds(60));
        session.start(now.minusSeconds(60));
        // Left: forefoot-heavy steps of 6 frames; right: heel-heavy steps of 3 frames; 3 unloaded frames between.
        List<Integer> leftLoaded = List.of(100, 100, 100, 100, 1000, 1000, 1000, 800);
        List<Integer> rightLoaded = List.of(1500, 1500, 100, 100, 100, 100, 100, 100);
        List<Integer> unloaded = List.of(100, 100, 100, 100, 100, 100, 100, 100);
        List<StoredPressureFrame> frames = new ArrayList<>();
        frames.addAll(steps(leftDevice, FootSide.LEFT, 5, 6, 3, leftLoaded, unloaded, now));
        frames.addAll(steps(rightDevice, FootSide.RIGHT, 5, 3, 3, rightLoaded, unloaded, now));
        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer(calibrations, layouts, new ObjectMapper(),
                TestAnalysisProperties.defaults());

        ComputedAnalysis computed = analyzer.analyze(session, frames, null);

        assertThat(computed.validStepCount()).isEqualTo(10);
        assertThat(computed.observationSummary()).extracting(ObservationSummaryItem::code)
                .containsExactlyElementsOf(PatternCatalog.CODES);
        java.util.Map<String, ObservationSummaryItem> byCode = new java.util.HashMap<>();
        computed.observationSummary().forEach(item -> byCode.put(item.code(), item));
        ObservationSummaryItem asymmetry = byCode.get("LEFT_RIGHT_ASYMMETRY");
        assertThat(asymmetry.observationLevel()).isEqualTo(ObservationLevel.REPEATEDLY_OBSERVED);
        assertThat(asymmetry.windowCount()).isEqualTo(5);
        assertThat(asymmetry.observedCount()).isEqualTo(5);
        assertThat(byCode.get("LATERAL_LOAD_TENDENCY").observationLevel()).isEqualTo(ObservationLevel.NOT_OBSERVED);
        for (String code : List.of("MEDIAL_LOAD_TENDENCY", "FOREFOOT_LOAD_TENDENCY", "REARFOOT_LOAD_TENDENCY",
                "LOW_HALLUX_SIGNAL")) {
            assertThat(byCode.get(code).windowCount()).as(code).isEqualTo(10);
            assertThat(byCode.get(code).observedCount()).as(code).isEqualTo(5);
            assertThat(byCode.get(code).observationLevel()).as(code).isEqualTo(ObservationLevel.PARTIALLY_OBSERVED);
        }
        assertThat(computed.patterns()).extracting(ComputedPattern::code).containsExactly(
                "LEFT_RIGHT_ASYMMETRY", "MEDIAL_LOAD_TENDENCY", "LOW_HALLUX_SIGNAL", "FOREFOOT_LOAD_TENDENCY",
                "REARFOOT_LOAD_TENDENCY");
        assertThat(computed.patterns().getFirst().severity()).isEqualTo(PatternSeverity.CAUTION);
        assertThat(computed.patterns().getFirst().evidence()).contains("5회 중 5회");
        assertThat(computed.patterns()).extracting(ComputedPattern::code)
                .doesNotContain("HIGH_MIDFOOT_LOAD", "SHORT_CONTACT_TIME", "LOW_DATA_QUALITY");
        assertThat(computed.recommendationCodes()).containsExactly("ANKLE_STABILITY_BASIC", "BALANCED_FOOT_LOADING");
        assertThat(computed.qualityFlags()).doesNotContain("LOW_DATA_QUALITY");
        assertThat(computed.distribution().leftSensorSharePct()).hasSize(8);
        assertThat(computed.distribution().leftSensorSharePct().stream().mapToDouble(Double::doubleValue).sum())
                .isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-6));
        assertThat(computed.distribution().rightSensorSharePct().get(0))
                .isGreaterThan(computed.distribution().leftSensorSharePct().get(0));
        // rule-v1.3.0: left steps repeat every 9 frames (180 ms), right steps every 6 frames (120 ms); the first
        // interval of each foot is one frame shorter because smoothing pulls later window starts one frame earlier,
        // so the median (of 4 intervals) is the repeated value. Left windows carry the higher frame totals.
        assertThat(computed.leftStrideTimeMs()).isEqualTo(180.0);
        assertThat(computed.rightStrideTimeMs()).isEqualTo(120.0);
        assertThat(computed.meanStrideTimeMs()).isEqualTo(150.0);
        assertThat(computed.distribution().leftLoadSharePct()).isGreaterThan(50.0).isLessThan(100.0);
        assertThat(computed.distribution().leftLoadSharePct() + computed.distribution().rightLoadSharePct())
                .isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
        // rule-v1.4.0: imuAvailable without stored accel/gyro vectors counts as no IMU frame at all.
        assertThat(computed.movementSummary()).isNull();
    }

    @Test
    void oneFootWithoutFramesLeavesLoadShareAndItsStrideNullButKeepsTheOtherFootsStride() {
        Instant now = Instant.parse("2026-09-10T01:00:00Z");
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        CalibrationProfile leftCalibration = CalibrationProfile.identity(leftDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", now);
        CalibrationProfile rightCalibration = CalibrationProfile.identity(rightDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", now);
        CalibrationProfileRepository calibrations = mock(CalibrationProfileRepository.class);
        SensorLayoutRepository layouts = mock(SensorLayoutRepository.class);
        when(calibrations.findById(leftCalibration.getId())).thenReturn(Optional.of(leftCalibration));
        when(calibrations.findById(rightCalibration.getId())).thenReturn(Optional.of(rightCalibration));
        when(layouts.findById("layout-s01s08-v1")).thenReturn(Optional.of(new SensorLayout("layout-s01s08-v1", 8,
                s01s08Points(), true, now)));
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), leftDevice, rightDevice,
                leftCalibration.getId(), rightCalibration.getId(), "layout-s01s08-v1", "layout-s01s08-v1", 50,
                SourceType.DEVICE, 4095, null, now.minusSeconds(60));
        session.start(now.minusSeconds(60));
        List<Integer> loaded = List.of(100, 100, 100, 100, 1000, 1000, 1000, 800);
        List<Integer> unloaded = List.of(100, 100, 100, 100, 100, 100, 100, 100);
        List<StoredPressureFrame> frames = steps(leftDevice, FootSide.LEFT, 5, 6, 3, loaded, unloaded, now);
        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer(calibrations, layouts, new ObjectMapper(),
                TestAnalysisProperties.defaults());

        ComputedAnalysis computed = analyzer.analyze(session, frames, null);

        assertThat(computed.validStepCount()).isEqualTo(5);
        assertThat(computed.distribution().leftLoadSharePct()).isNull();
        assertThat(computed.distribution().rightLoadSharePct()).isNull();
        assertThat(computed.leftStrideTimeMs()).isEqualTo(180.0);
        assertThat(computed.rightStrideTimeMs()).isNull();
        assertThat(computed.meanStrideTimeMs()).isEqualTo(180.0);
        assertThat(computed.qualityFlags()).contains("RIGHT_DATA_MISSING");
    }

    @Test
    void differentSensorCountsPerFootLeaveLoadShareNullButKeepStrideAndContactMetrics() {
        Instant now = Instant.parse("2026-09-11T01:00:00Z");
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        CalibrationProfile leftCalibration = CalibrationProfile.identity(leftDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", now);
        CalibrationProfile rightCalibration = CalibrationProfile.identity(rightDevice,
                "[0,0,0,0,0,0]", "[1,1,1,1,1,1]", now);
        CalibrationProfileRepository calibrations = mock(CalibrationProfileRepository.class);
        SensorLayoutRepository layouts = mock(SensorLayoutRepository.class);
        when(calibrations.findById(leftCalibration.getId())).thenReturn(Optional.of(leftCalibration));
        when(calibrations.findById(rightCalibration.getId())).thenReturn(Optional.of(rightCalibration));
        when(layouts.findById("layout-s01s08-v1")).thenReturn(Optional.of(new SensorLayout("layout-s01s08-v1", 8,
                s01s08Points(), true, now)));
        when(layouts.findById("layout-v1-6")).thenReturn(Optional.of(new SensorLayout("layout-v1-6", 6,
                layoutV16Points(), false, now)));
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), leftDevice, rightDevice,
                leftCalibration.getId(), rightCalibration.getId(), "layout-s01s08-v1", "layout-v1-6", 50,
                SourceType.DEVICE, 4095, null, now.minusSeconds(60));
        session.start(now.minusSeconds(60));
        // Same step timing as the eight-sensor test: left every 9 frames, right (6 sensors) every 6 frames.
        List<Integer> leftLoaded = List.of(100, 100, 100, 100, 1000, 1000, 1000, 800);
        List<Integer> leftUnloaded = List.of(100, 100, 100, 100, 100, 100, 100, 100);
        List<Integer> rightLoaded = List.of(1500, 1500, 100, 100, 100, 100);
        List<Integer> rightUnloaded = List.of(100, 100, 100, 100, 100, 100);
        List<StoredPressureFrame> frames = new ArrayList<>();
        frames.addAll(steps(leftDevice, FootSide.LEFT, 5, 6, 3, leftLoaded, leftUnloaded, now));
        frames.addAll(steps(rightDevice, FootSide.RIGHT, 5, 3, 3, rightLoaded, rightUnloaded, now));
        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer(calibrations, layouts, new ObjectMapper(),
                TestAnalysisProperties.defaults());

        ComputedAnalysis computed = analyzer.analyze(session, frames, null);

        assertThat(computed.distribution().leftLoadSharePct()).isNull();
        assertThat(computed.distribution().rightLoadSharePct()).isNull();
        assertThat(computed.validStepCount()).isEqualTo(10);
        assertThat(computed.leftStrideTimeMs()).isEqualTo(180.0);
        assertThat(computed.rightStrideTimeMs()).isEqualTo(120.0);
        assertThat(computed.meanStrideTimeMs()).isEqualTo(150.0);
        assertThat(computed.leftContactTimeMs()).isGreaterThan(0.0);
        assertThat(computed.rightContactTimeMs()).isGreaterThan(0.0);
        assertThat(computed.distribution().leftSensorSharePct()).hasSize(8);
        assertThat(computed.distribution().rightSensorSharePct()).hasSize(6);
        assertThat(computed.qualityFlags()).doesNotContain("LEFT_DATA_MISSING", "RIGHT_DATA_MISSING");
    }

    @Test
    void loadShareUsesEachFootsMeanContactFrameTotalAndSumsTo100() {
        List<RuleBasedAnalyzer.NormalizedFrame> left = List.of(
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.LEFT, 1, 0, Instant.EPOCH, List.of(10.0, 30.0, 60.0, 0.0)),
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.LEFT, 2, 10, Instant.EPOCH, List.of(50.0, 50.0, 0.0, 0.0)),
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.LEFT, 3, 20, Instant.EPOCH, List.of(5.0, 5.0, 0.0, 0.0)),
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.LEFT, 4, 30, Instant.EPOCH, List.of(90.0, 90.0, 0.0, 0.0)));
        List<RuleBasedAnalyzer.NormalizedFrame> right = List.of(
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.RIGHT, 1, 0, Instant.EPOCH, List.of(10.0, 10.0, 10.0, 10.0)),
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.RIGHT, 2, 10, Instant.EPOCH, List.of(20.0, 20.0, 20.0, 20.0)));
        // Left contact frames: totals 100, 100 (index 2 is outside every window) and 180 -> mean 380 / 3.
        List<RuleBasedAnalyzer.ContactWindow> leftWindows = List.of(
                new RuleBasedAnalyzer.ContactWindow(0, 1, 0, 10, 20), new RuleBasedAnalyzer.ContactWindow(3, 3, 30, 30, 10));
        // Right contact frames: totals 40 and 80 -> mean 60.
        List<RuleBasedAnalyzer.ContactWindow> rightWindows = List.of(new RuleBasedAnalyzer.ContactWindow(0, 1, 0, 10, 20));

        Double leftMean = RuleBasedAnalyzer.meanContactTotal(left, leftWindows);
        Double rightMean = RuleBasedAnalyzer.meanContactTotal(right, rightWindows);
        RuleBasedAnalyzer.LoadShare share = RuleBasedAnalyzer.loadShare(leftMean, rightMean);

        assertThat(leftMean).isCloseTo(380.0 / 3.0, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(rightMean).isEqualTo(60.0);
        double expectedLeft = (380.0 / 3.0) / (380.0 / 3.0 + 60.0) * 100.0;
        assertThat(share.leftPct()).isCloseTo(expectedLeft, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(share.rightPct()).isCloseTo(100.0 - expectedLeft, org.assertj.core.data.Offset.offset(1e-9));
        assertThat(share.leftPct() + share.rightPct()).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1e-9));
    }

    @Test
    void loadShareIsNullWhenEitherFootHasNoContactWindow() {
        List<RuleBasedAnalyzer.NormalizedFrame> frames = List.of(
                new RuleBasedAnalyzer.NormalizedFrame(FootSide.RIGHT, 1, 0, Instant.EPOCH, List.of(10.0, 10.0, 10.0, 10.0)));

        assertThat(RuleBasedAnalyzer.meanContactTotal(frames, List.of())).isNull();
        assertThat(RuleBasedAnalyzer.meanContactTotal(List.of(), List.of())).isNull();
        assertThat(RuleBasedAnalyzer.loadShare(null, 40.0)).isEqualTo(RuleBasedAnalyzer.LoadShare.NONE);
        assertThat(RuleBasedAnalyzer.loadShare(40.0, null)).isEqualTo(RuleBasedAnalyzer.LoadShare.NONE);
        assertThat(RuleBasedAnalyzer.loadShare(null, null)).isEqualTo(RuleBasedAnalyzer.LoadShare.NONE);
        assertThat(RuleBasedAnalyzer.loadShare(0.0, 0.0)).isEqualTo(RuleBasedAnalyzer.LoadShare.NONE);
        assertThat(RuleBasedAnalyzer.loadShare(40.0, 40.0).leftPct()).isEqualTo(50.0);
        assertThat(RuleBasedAnalyzer.loadShare(40.0, 40.0).rightPct()).isEqualTo(50.0);
    }

    @Test
    void strideTimeIsTheMedianStartToStartIntervalOfConsecutiveWindows() {
        // Window starts 0, 1000, 2100, 3100, 5100 -> intervals 1000, 1100, 1000, 2000 -> sorted median (1000+1100)/2.
        List<RuleBasedAnalyzer.ContactWindow> five = List.of(
                new RuleBasedAnalyzer.ContactWindow(0, 5, 0, 500, 510),
                new RuleBasedAnalyzer.ContactWindow(10, 15, 1000, 1500, 510),
                new RuleBasedAnalyzer.ContactWindow(20, 25, 2100, 2600, 510),
                new RuleBasedAnalyzer.ContactWindow(30, 35, 3100, 3600, 510),
                new RuleBasedAnalyzer.ContactWindow(40, 45, 5100, 5600, 510));
        // Window starts 0, 900, 2500 -> intervals 900, 1600 -> even count: (900 + 1600) / 2.
        List<RuleBasedAnalyzer.ContactWindow> three = List.of(
                new RuleBasedAnalyzer.ContactWindow(0, 5, 0, 500, 510),
                new RuleBasedAnalyzer.ContactWindow(10, 15, 900, 1500, 610),
                new RuleBasedAnalyzer.ContactWindow(20, 25, 2500, 2600, 110));

        assertThat(RuleBasedAnalyzer.strideTimeMs(five)).isEqualTo(1050.0);
        // 3 windows -> intervals 1000, 1100 (even count) -> 1050; 4 windows -> 1000, 1100, 1000 (odd) -> 1000.
        assertThat(RuleBasedAnalyzer.strideTimeMs(five.subList(0, 3))).isEqualTo(1050.0);
        assertThat(RuleBasedAnalyzer.strideTimeMs(five.subList(0, 4))).isEqualTo(1000.0);
        assertThat(RuleBasedAnalyzer.strideTimeMs(three)).isEqualTo(1250.0);
        assertThat(RuleBasedAnalyzer.strideTimeMs(five.subList(0, 2))).isEqualTo(1000.0);
        assertThat(RuleBasedAnalyzer.strideTimeMs(five.subList(0, 1))).isNull();
        assertThat(RuleBasedAnalyzer.strideTimeMs(List.of())).isNull();
    }

    @Test
    void meanStrideTimeAveragesBothFeetOrFallsBackToTheSingleAvailableFoot() {
        assertThat(RuleBasedAnalyzer.meanStrideTimeMs(1000.0, 1200.0)).isEqualTo(1100.0);
        assertThat(RuleBasedAnalyzer.meanStrideTimeMs(1000.0, null)).isEqualTo(1000.0);
        assertThat(RuleBasedAnalyzer.meanStrideTimeMs(null, 1200.0)).isEqualTo(1200.0);
        assertThat(RuleBasedAnalyzer.meanStrideTimeMs(null, null)).isNull();
    }

    @Test
    void lowQualityScoreFlagsLowDataQualityAndRecommendsRemeasurementWithoutAPattern() {
        ObjectMapper mapper = new ObjectMapper();
        MeasurementQualityStats stats = MeasurementQualityStats.create(UUID.randomUUID(), Instant.now());
        stats.apply(10, 0, 0, new MeasurementQualityStats.SideCursor(1, 10, 100), null, Set.of(), mapper,
                Instant.now());
        stats.finalizeForSession(200, 0, Set.of("RIGHT_DATA_MISSING"), mapper, Instant.now());
        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer(mock(CalibrationProfileRepository.class),
                mock(SensorLayoutRepository.class), mapper, TestAnalysisProperties.defaults());

        RuleBasedAnalyzer.QualitySummary quality = analyzer.qualityForAvailability(stats, false, true, 10);

        assertThat(quality.score()).isLessThan(60);
        assertThat(quality.flags()).contains("LOW_DATA_QUALITY", "RIGHT_DATA_MISSING");
    }

    private static List<StoredPressureFrame> steps(UUID deviceId, FootSide side, int stepCount, int loadedFrames,
                                                   int unloadedFrames, List<Integer> loaded, List<Integer> unloaded,
                                                   Instant receivedAt) {
        List<StoredPressureFrame> frames = new ArrayList<>();
        long sequence = 0;
        for (int step = 0; step < stepCount; step++) {
            for (int index = 0; index < loadedFrames + unloadedFrames; index++) {
                List<Integer> values = index < loadedFrames ? loaded : unloaded;
                long deviceTimeMs = sequence * 20;
                frames.add(new StoredPressureFrame(deviceId, side, sequence, deviceTimeMs, receivedAt, values, 1,
                        receivedAt.plusMillis(deviceTimeMs), DataMode.RAW, false, true, null, null, null));
                sequence++;
            }
        }
        return frames;
    }

    private static String s01s08Points() {
        return """
                [{"index":0,"label":"S01","x":0.40,"y":0.88,"region":"HEEL","medialLateral":"MEDIAL"},
                 {"index":1,"label":"S02","x":0.62,"y":0.88,"region":"HEEL","medialLateral":"LATERAL"},
                 {"index":2,"label":"S03","x":0.36,"y":0.62,"region":"MIDFOOT","medialLateral":"MEDIAL"},
                 {"index":3,"label":"S04","x":0.66,"y":0.62,"region":"MIDFOOT","medialLateral":"LATERAL"},
                 {"index":4,"label":"S05","x":0.32,"y":0.36,"region":"FOREFOOT","medialLateral":"MEDIAL"},
                 {"index":5,"label":"S06","x":0.50,"y":0.34,"region":"FOREFOOT","medialLateral":"CENTER"},
                 {"index":6,"label":"S07","x":0.70,"y":0.38,"region":"FOREFOOT","medialLateral":"LATERAL"},
                 {"index":7,"label":"S08","x":0.36,"y":0.12,"region":"TOE","medialLateral":"MEDIAL"}]
                """;
    }

    private static String layoutV16Points() {
        return """
                [{"index":0,"x":0.50,"y":0.90,"region":"HEEL","medialLateral":"CENTER"},
                 {"index":1,"x":0.36,"y":0.68,"region":"MIDFOOT","medialLateral":"MEDIAL"},
                 {"index":2,"x":0.64,"y":0.68,"region":"MIDFOOT","medialLateral":"LATERAL"},
                 {"index":3,"x":0.30,"y":0.42,"region":"FOREFOOT","medialLateral":"MEDIAL"},
                 {"index":4,"x":0.50,"y":0.40,"region":"FOREFOOT","medialLateral":"CENTER"},
                 {"index":5,"x":0.70,"y":0.42,"region":"FOREFOOT","medialLateral":"LATERAL"}]
                """;
    }

    private static List<SensorPoint> points() {
        return List.of(
                new SensorPoint(0, 0.0, 1.0, "HEEL", "CENTER"),
                new SensorPoint(1, 0.25, 0.6, "MIDFOOT", "MEDIAL"),
                new SensorPoint(2, 0.75, 0.3, "FOREFOOT", "LATERAL"),
                new SensorPoint(3, 0.5, 0.0, "TOE", "MEDIAL"));
    }
}
