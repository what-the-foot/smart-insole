package com.smartinsole.analysis;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.smartinsole.analysis.AnalysisDtos.PressureDistribution;
import com.smartinsole.calibration.CalibrationProfileRepository;
import com.smartinsole.device.DeviceDtos.SensorPoint;
import com.smartinsole.device.SensorLayoutRepository;
import com.smartinsole.measurement.MeasurementQualityStats;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.List;
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

    private static List<SensorPoint> points() {
        return List.of(
                new SensorPoint(0, 0.0, 1.0, "HEEL", "CENTER"),
                new SensorPoint(1, 0.25, 0.6, "MIDFOOT", "MEDIAL"),
                new SensorPoint(2, 0.75, 0.3, "FOREFOOT", "LATERAL"),
                new SensorPoint(3, 0.5, 0.0, "TOE", "MEDIAL"));
    }
}
