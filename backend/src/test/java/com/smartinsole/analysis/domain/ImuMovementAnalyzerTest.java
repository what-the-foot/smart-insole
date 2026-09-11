package com.smartinsole.analysis.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.dto.AnalysisDtos.ComputedAnalysis;
import com.smartinsole.analysis.dto.AnalysisDtos.MovementFootSummary;
import com.smartinsole.analysis.dto.AnalysisDtos.MovementReferenceMethod;
import com.smartinsole.analysis.dto.AnalysisDtos.MovementSummary;
import com.smartinsole.calibration.domain.CalibrationProfile;
import com.smartinsole.calibration.repository.CalibrationProfileRepository;
import com.smartinsole.device.domain.SensorLayout;
import com.smartinsole.device.repository.SensorLayoutRepository;
import com.smartinsole.global.common.DomainTypes.DataMode;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.SourceType;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.repository.PressureFrameRepository.StoredPressureFrame;
import com.smartinsole.support.TestAnalysisProperties;
import com.smartinsole.support.TestSessions;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.IntPredicate;
import org.junit.jupiter.api.Test;

/**
 * Synthetic shank trajectories in a canonical sensor frame (x = forward, y = left, z = up, right-handed)
 * rotated by a fixed arbitrary mounting rotation. Quiet standing (2 s, loaded, gyro = bias only) is followed
 * by ten gait cycles of a 400 ms swing (unloaded, gyro about y = -250 sin(pi k / 20) deg/s, i.e. the forward
 * swing is a negative rotation about the left axis) and a 600 ms stance (loaded, gyro = (0, 50, 20) deg/s,
 * gravity tilted by the frontal angle in the +y direction). Pressure: loaded 307 raw per sensor (about 60
 * normalised total, so the 3-frame smoothing keeps the windows exactly on the loaded frames), unloaded 0.
 */
class ImuMovementAnalyzerTest {
    private static final int SAMPLE_RATE = 50;
    private static final int STEP_MS = 1000 / SAMPLE_RATE;
    private static final int STANDING_FRAMES = 100;
    private static final int CYCLES = 10;
    private static final int SWING_FRAMES = 20;
    private static final int STANCE_FRAMES = 30;
    private static final double STANCE_SAGITTAL_DPS = 50.0;
    private static final double STANCE_TRANSVERSE_DPS = 20.0;
    private static final double SWING_PEAK_DPS = 250.0;
    /** Constant 50 deg/s over 29 intervals of 20 ms. */
    private static final double EXPECTED_SAGITTAL_RANGE = STANCE_SAGITTAL_DPS * (STANCE_FRAMES - 1) * STEP_MS / 1000.0;
    private static final double EXPECTED_TRANSVERSE_RANGE =
            STANCE_TRANSVERSE_DPS * (STANCE_FRAMES - 1) * STEP_MS / 1000.0;
    private static final double[][] IDENTITY = {{1, 0, 0}, {0, 1, 0}, {0, 0, 1}};
    /** Arbitrary but fixed mounting rotation: 2.0 rad about the unit axis (1, 2, -2) / 3. */
    private static final double[][] MOUNTING = rotation(new double[] {1.0 / 3, 2.0 / 3, -2.0 / 3}, 2.0);
    private static final double[] BIAS_DPS = {2.0, -1.5, 0.7};
    private static final Instant NOW = Instant.parse("2026-09-11T01:00:00Z");
    private static final IntPredicate ALL = index -> true;

    @Test
    void recoversCanonicalShankMetricsUnderAnArbitraryMountingRotationWithMirroredLateralSigns() throws Exception {
        double tilt = 11.0;
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        List<StoredPressureFrame> rotated = new ArrayList<>();
        rotated.addAll(trajectory(leftDevice, FootSide.LEFT, true, tilt, MOUNTING, BIAS_DPS, ALL));
        rotated.addAll(trajectory(rightDevice, FootSide.RIGHT, true, tilt, MOUNTING, BIAS_DPS, ALL));
        List<StoredPressureFrame> canonical = new ArrayList<>();
        canonical.addAll(trajectory(leftDevice, FootSide.LEFT, true, tilt, IDENTITY, new double[3], ALL));
        canonical.addAll(trajectory(rightDevice, FootSide.RIGHT, true, tilt, IDENTITY, new double[3], ALL));

        MovementSummary summary = analyze(leftDevice, rightDevice, rotated).movementSummary();
        MovementSummary reference = analyze(leftDevice, rightDevice, canonical).movementSummary();

        assertThat(summary).isNotNull();
        assertThat(summary.imuCoverage()).isEqualTo(1.0);
        assertThat(summary.referenceMethod()).isEqualTo(MovementReferenceMethod.QUIET_STANDING);
        // The quiet-standing window defines the reference and is excluded from the per-window metrics.
        int windows = CYCLES;
        double expectedTilt = tilt;
        for (MovementFootSummary foot : List.of(summary.left(), summary.right())) {
            assertThat(foot.windowCount()).isEqualTo(windows);
            assertThat(foot.sagittalRangeDeg()).isCloseTo(EXPECTED_SAGITTAL_RANGE, within(0.5));
            assertThat(foot.transverseRangeDeg()).isCloseTo(EXPECTED_TRANSVERSE_RANGE, within(0.5));
            assertThat(foot.swingPeakAngularVelocityDps()).isCloseTo(SWING_PEAK_DPS, within(1.0));
        }
        // Gravity tilted toward +y (the body's left): lateral for the LEFT board, medial for the RIGHT board.
        assertThat(summary.left().frontalTiltDeg()).isCloseTo(expectedTilt, within(0.5));
        assertThat(summary.right().frontalTiltDeg()).isCloseTo(-expectedTilt, within(0.5));
        // The rotated session reproduces the un-rotated one up to int16 quantisation.
        assertThat(summary.left().frontalTiltDeg()).isCloseTo(reference.left().frontalTiltDeg(), within(0.2));
        assertThat(summary.right().frontalTiltDeg()).isCloseTo(reference.right().frontalTiltDeg(), within(0.2));
        assertThat(summary.left().sagittalRangeDeg()).isCloseTo(reference.left().sagittalRangeDeg(), within(0.2));
        assertThat(summary.left().transverseRangeDeg()).isCloseTo(reference.left().transverseRangeDeg(), within(0.2));
        assertThat(summary.left().swingPeakAngularVelocityDps())
                .isCloseTo(reference.left().swingPeakAngularVelocityDps(), within(0.5));
        // The persisted V9 document (Jackson record serialisation) round-trips with the contract key names.
        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(summary);
        assertThat(json).contains("\"imuCoverage\":1.0", "\"referenceMethod\":\"QUIET_STANDING\"",
                "\"frontalTiltDeg\"", "\"sagittalRangeDeg\"", "\"transverseRangeDeg\"",
                "\"swingPeakAngularVelocityDps\"", "\"windowCount\":10");
        assertThat(mapper.readValue(json, MovementSummary.class)).isEqualTo(summary);
    }

    @Test
    void framesWithoutImuVectorsLeaveTheWholeSummaryNull() {
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        List<StoredPressureFrame> frames = new ArrayList<>();
        frames.addAll(trajectory(leftDevice, FootSide.LEFT, true, 5.0, MOUNTING, BIAS_DPS, index -> false));
        frames.addAll(trajectory(rightDevice, FootSide.RIGHT, true, 5.0, MOUNTING, BIAS_DPS, index -> false));

        ComputedAnalysis computed = analyze(leftDevice, rightDevice, frames);

        assertThat(computed.movementSummary()).isNull();
        assertThat(computed.validStepCount()).isEqualTo(2 * (CYCLES + 1));
        assertThat(ImuMovementAnalyzer.analyze(List.of(), List.of(), List.of(), List.of(), SAMPLE_RATE,
                AnalysisProperties.Movement.defaults())).isNull();
    }

    @Test
    void lowImuCoverageKeepsTheCoverageButNoReferenceOrFootValues() {
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        IntPredicate everyThirdOfTen = index -> index % 10 < 3;
        List<StoredPressureFrame> frames = new ArrayList<>();
        frames.addAll(trajectory(leftDevice, FootSide.LEFT, true, 5.0, MOUNTING, BIAS_DPS, everyThirdOfTen));
        frames.addAll(trajectory(rightDevice, FootSide.RIGHT, true, 5.0, MOUNTING, BIAS_DPS, everyThirdOfTen));

        MovementSummary summary = analyze(leftDevice, rightDevice, frames).movementSummary();

        assertThat(summary).isNotNull();
        assertThat(summary.imuCoverage()).isCloseTo(0.3, within(1e-9));
        assertThat(summary.referenceMethod()).isNull();
        assertThat(summary.left()).isNull();
        assertThat(summary.right()).isNull();
    }

    @Test
    void fallsBackToTheFirstStanceReferenceWhenNoQuietIntervalExists() {
        double tilt = 5.0;
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        List<StoredPressureFrame> frames = new ArrayList<>();
        frames.addAll(trajectory(leftDevice, FootSide.LEFT, false, tilt, MOUNTING, new double[3], ALL));
        frames.addAll(trajectory(rightDevice, FootSide.RIGHT, false, tilt, MOUNTING, new double[3], ALL));

        MovementSummary summary = analyze(leftDevice, rightDevice, frames).movementSummary();

        assertThat(summary).isNotNull();
        assertThat(summary.referenceMethod()).isEqualTo(MovementReferenceMethod.FIRST_STANCE);
        for (MovementFootSummary foot : List.of(summary.left(), summary.right())) {
            assertThat(foot.windowCount()).isEqualTo(CYCLES);
            // The reference is the stance posture itself, so the tilt relative to it is zero.
            assertThat(foot.frontalTiltDeg()).isCloseTo(0.0, within(0.5));
            // Up is tilted by 5 deg against the gyro axes, which mixes the two stance components slightly.
            assertThat(foot.sagittalRangeDeg()).isCloseTo(EXPECTED_SAGITTAL_RANGE, within(2.0));
            assertThat(foot.transverseRangeDeg()).isCloseTo(EXPECTED_TRANSVERSE_RANGE, within(3.0));
            assertThat(foot.swingPeakAngularVelocityDps()).isCloseTo(SWING_PEAK_DPS, within(2.0));
        }
    }

    @Test
    void saturatedSamplesAreExcludedFromTheirWindowAndSwing() {
        double tilt = 11.0;
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        List<StoredPressureFrame> left = trajectory(leftDevice, FootSide.LEFT, true, tilt, MOUNTING, BIAS_DPS, ALL);
        int cycleFrames = SWING_FRAMES + STANCE_FRAMES;
        // A saturated gyro sample inside the third swing and a fully saturated accel in the fifth stance window.
        int swingSample = STANDING_FRAMES + 2 * cycleFrames + 5;
        int stanceStart = STANDING_FRAMES + 4 * cycleFrames + SWING_FRAMES;
        List<StoredPressureFrame> tampered = new ArrayList<>();
        for (StoredPressureFrame frame : left) {
            int index = (int) frame.sequence();
            if (index == swingSample) {
                tampered.add(with(frame, frame.accelMg(), List.of(32767, frame.gyroDps10().get(1), frame.gyroDps10().get(2))));
            } else if (index >= stanceStart && index < stanceStart + STANCE_FRAMES) {
                tampered.add(with(frame, List.of(frame.accelMg().get(0), frame.accelMg().get(1), -32760), frame.gyroDps10()));
            } else {
                tampered.add(frame);
            }
        }
        List<StoredPressureFrame> frames = new ArrayList<>(tampered);
        frames.addAll(trajectory(rightDevice, FootSide.RIGHT, true, tilt, MOUNTING, BIAS_DPS, ALL));

        MovementSummary summary = analyze(leftDevice, rightDevice, frames).movementSummary();

        assertThat(summary.imuCoverage()).isEqualTo(1.0);
        assertThat(summary.left().windowCount()).isEqualTo(CYCLES - 1);
        assertThat(summary.right().windowCount()).isEqualTo(CYCLES);
        assertThat(summary.left().frontalTiltDeg()).isCloseTo(tilt, within(0.5));
        assertThat(summary.left().sagittalRangeDeg()).isCloseTo(EXPECTED_SAGITTAL_RANGE, within(0.5));
        assertThat(summary.left().swingPeakAngularVelocityDps()).isCloseTo(SWING_PEAK_DPS, within(1.0));
    }

    @Test
    void quietStandingRequiresContactQuietSamplesAndTheMinimumDuration() {
        AnalysisProperties.Movement thresholds = AnalysisProperties.Movement.defaults();
        double period = 1000.0 / SAMPLE_RATE;
        // 50 quiet contact frames at 20 ms = exactly 1.0 s; 49 frames fall short.
        assertThat(ImuMovementAnalyzer.quietStandingReference(quiet(50, true, 0.0), period, thresholds)).isNotNull();
        assertThat(ImuMovementAnalyzer.quietStandingReference(quiet(49, true, 0.0), period, thresholds)).isNull();
        assertThat(ImuMovementAnalyzer.quietStandingReference(quiet(50, false, 0.0), period, thresholds)).isNull();
        assertThat(ImuMovementAnalyzer.quietStandingReference(quiet(50, true, 12.0), period, thresholds)).isNull();
        ImuMovementAnalyzer.Reference reference = ImuMovementAnalyzer.quietStandingReference(
                quiet(60, true, 3.0), period, thresholds);
        assertThat(reference).isNotNull();
        assertThat(reference.up()).containsExactly(0.0, 0.0, 1.0);
        assertThat(reference.gyroBias()[0]).isCloseTo(3.0, within(1e-9));
        assertThat(reference.firstIndex()).isZero();
        assertThat(reference.lastIndex()).isEqualTo(59);
    }

    @Test
    void oneContactWindowPerFootKeepsTheReferenceButReportsNoUsableWindows() {
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        List<StoredPressureFrame> frames = new ArrayList<>();
        frames.addAll(trajectory(leftDevice, FootSide.LEFT, false, 5.0, MOUNTING, BIAS_DPS, ALL, 1));
        frames.addAll(trajectory(rightDevice, FootSide.RIGHT, false, 5.0, MOUNTING, BIAS_DPS, ALL, 1));

        MovementSummary summary = analyze(leftDevice, rightDevice, frames).movementSummary();

        // One stance per foot: the FIRST_STANCE reference exists, but without a swing the axis sign is
        // undefined, so the foot keeps its object with windowCount 0 and null metrics (contract 1.3.0).
        assertThat(summary.imuCoverage()).isEqualTo(1.0);
        assertThat(summary.referenceMethod()).isEqualTo(MovementReferenceMethod.FIRST_STANCE);
        MovementFootSummary empty = new MovementFootSummary(null, null, null, null, 0);
        assertThat(summary.left()).isEqualTo(empty);
        assertThat(summary.right()).isEqualTo(empty);
    }

    @Test
    void quietStandingOnlySessionKeepsTheQuietReferenceButHasNoRotationToAlignOn() {
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        List<StoredPressureFrame> frames = new ArrayList<>();
        frames.addAll(trajectory(leftDevice, FootSide.LEFT, true, 0.0, MOUNTING, BIAS_DPS, ALL, 0));
        frames.addAll(trajectory(rightDevice, FootSide.RIGHT, true, 0.0, MOUNTING, BIAS_DPS, ALL, 0));

        MovementSummary summary = analyze(leftDevice, rightDevice, frames).movementSummary();

        // Standing only: the bias-corrected gyro is zero, so PCA finds no axis; the reference is still reported.
        assertThat(summary.referenceMethod()).isEqualTo(MovementReferenceMethod.QUIET_STANDING);
        MovementFootSummary empty = new MovementFootSummary(null, null, null, null, 0);
        assertThat(summary.left()).isEqualTo(empty);
        assertThat(summary.right()).isEqualTo(empty);
    }

    @Test
    void deviceTimeRestartMidSessionIsOrderedBySequenceAndLeavesTheWindowMetricsUnchanged() {
        double tilt = 11.0;
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        List<StoredPressureFrame> left = trajectory(leftDevice, FootSide.LEFT, true, tilt, MOUNTING, BIAS_DPS, ALL);
        List<StoredPressureFrame> right = trajectory(rightDevice, FootSide.RIGHT, true, tilt, MOUNTING, BIAS_DPS, ALL);
        // Receiver RESET (BLE_PROTOCOL §5) at the start of the fifth swing: deviceTimeMs restarts at 0 while the
        // unwrapped sequence keeps increasing. Ordered by deviceTimeMs the post-reset frames would interleave
        // with the standing frames; ordered by sequence the windows and their metrics are untouched.
        int resetAt = STANDING_FRAMES + 4 * (SWING_FRAMES + STANCE_FRAMES);
        List<StoredPressureFrame> restarted = new ArrayList<>();
        for (StoredPressureFrame frame : left) {
            restarted.add(frame.sequence() < resetAt ? frame
                    : withDeviceTime(frame, (frame.sequence() - resetAt) * STEP_MS));
        }
        List<StoredPressureFrame> intact = new ArrayList<>(left);
        intact.addAll(right);
        restarted.addAll(right);

        ComputedAnalysis expected = analyze(leftDevice, rightDevice, intact);
        ComputedAnalysis actual = analyze(leftDevice, rightDevice, restarted);

        assertThat(actual.validStepCount()).isEqualTo(expected.validStepCount());
        assertThat(actual.movementSummary().referenceMethod()).isEqualTo(MovementReferenceMethod.QUIET_STANDING);
        assertThat(actual.movementSummary().left()).isEqualTo(expected.movementSummary().left());
        assertThat(actual.movementSummary().right()).isEqualTo(expected.movementSummary().right());
        assertThat(actual.movementSummary().left().sagittalRangeDeg())
                .isCloseTo(EXPECTED_SAGITTAL_RANGE, within(0.5));
    }

    @Test
    void principalAxisRecoversTheDominantEigenvectorOfASymmetricMatrix() {
        double[] axis = ImuMovementAnalyzer.normalized(new double[] {0.3, -0.5, 0.8});
        double[] other = ImuMovementAnalyzer.orthogonalized(new double[] {1, 0, 0}, axis);
        double[][] matrix = new double[3][3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) {
                matrix[row][column] = 5 * axis[row] * axis[column] + 1 * other[row] * other[column];
            }
        }

        double[] principal = ImuMovementAnalyzer.principalAxis(matrix);

        assertThat(Math.abs(ImuMovementAnalyzer.dot(principal, axis))).isCloseTo(1.0, within(1e-9));
        assertThat(ImuMovementAnalyzer.principalAxis(new double[3][3])).isNull();
    }

    @Test
    void integratedRangeUsesTrapezoidsAndResetsWhenDeviceTimeGoesBackwards() {
        double[] axis = {0, 1, 0};
        List<ImuMovementAnalyzer.Sample> constant = List.of(sample(0, 50), sample(20, 50), sample(40, 50));
        List<ImuMovementAnalyzer.Sample> ramp = List.of(sample(0, 0), sample(20, 100));
        List<ImuMovementAnalyzer.Sample> backwards = List.of(sample(0, 50), sample(20, 50), sample(10, 50),
                sample(30, 50));

        assertThat(ImuMovementAnalyzer.integratedRange(constant, axis)).isCloseTo(2.0, within(1e-9));
        assertThat(ImuMovementAnalyzer.integratedRange(ramp, axis)).isCloseTo(1.0, within(1e-9));
        assertThat(ImuMovementAnalyzer.integratedRange(backwards, axis)).isCloseTo(1.0, within(1e-9));
        assertThat(ImuMovementAnalyzer.integratedRange(List.of(sample(0, 50)), axis)).isZero();
    }

    private static ImuMovementAnalyzer.Sample sample(long timeMs, double gyroY) {
        return new ImuMovementAnalyzer.Sample(timeMs, true, true, true, new double[] {0, 0, 1},
                new double[] {0, gyroY, 0});
    }

    private static ImuMovementAnalyzer.FootSamples quiet(int count, boolean contact, double gyroX) {
        List<ImuMovementAnalyzer.Sample> samples = new ArrayList<>();
        for (int index = 0; index < count; index++) {
            samples.add(new ImuMovementAnalyzer.Sample(index * STEP_MS, contact, true, true,
                    new double[] {0, 0, 1}, new double[] {gyroX, 0, 0}));
        }
        return new ImuMovementAnalyzer.FootSamples(samples, List.of());
    }

    private static StoredPressureFrame withDeviceTime(StoredPressureFrame frame, long deviceTimeMs) {
        return new StoredPressureFrame(frame.deviceId(), frame.footSide(), frame.sequence(), deviceTimeMs,
                frame.receivedAt(), frame.sensorValues(), frame.protocolVersion(), frame.receiverReceivedAt(),
                frame.dataMode(), frame.calibrated(), frame.imuAvailable(), frame.accelMg(), frame.gyroDps10(),
                frame.flags());
    }

    private static StoredPressureFrame with(StoredPressureFrame frame, List<Integer> accel, List<Integer> gyro) {
        return new StoredPressureFrame(frame.deviceId(), frame.footSide(), frame.sequence(), frame.deviceTimeMs(),
                frame.receivedAt(), frame.sensorValues(), frame.protocolVersion(), frame.receiverReceivedAt(),
                frame.dataMode(), frame.calibrated(), frame.imuAvailable(), accel, gyro, frame.flags());
    }

    private static ComputedAnalysis analyze(UUID leftDevice, UUID rightDevice, List<StoredPressureFrame> frames) {
        CalibrationProfile leftCalibration = CalibrationProfile.identity(leftDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", NOW);
        CalibrationProfile rightCalibration = CalibrationProfile.identity(rightDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", NOW);
        CalibrationProfileRepository calibrations = mock(CalibrationProfileRepository.class);
        SensorLayoutRepository layouts = mock(SensorLayoutRepository.class);
        when(calibrations.findById(leftCalibration.getId())).thenReturn(Optional.of(leftCalibration));
        when(calibrations.findById(rightCalibration.getId())).thenReturn(Optional.of(rightCalibration));
        when(layouts.findById("layout-s01s08-v1")).thenReturn(Optional.of(new SensorLayout("layout-s01s08-v1", 8,
                s01s08Points(), true, NOW)));
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), leftDevice, rightDevice,
                leftCalibration.getId(), rightCalibration.getId(), "layout-s01s08-v1", "layout-s01s08-v1",
                SAMPLE_RATE, SourceType.DEVICE, 4095, null, NOW.minusSeconds(60));
        session.start(NOW.minusSeconds(60));
        RuleBasedAnalyzer analyzer = new RuleBasedAnalyzer(calibrations, layouts, new ObjectMapper(),
                TestAnalysisProperties.defaults());
        return analyzer.analyze(session, frames, null);
    }

    /**
     * @param rotation   sensor = rotation x canonical (mounting orientation)
     * @param biasDps    gyro bias added in the sensor frame
     * @param imuPresent which frame indices carry an IMU sample (others: imuAvailable=false, null vectors)
     */
    private static List<StoredPressureFrame> trajectory(UUID device, FootSide side, boolean quietStanding,
                                                        double frontalTiltDeg, double[][] rotation,
                                                        double[] biasDps, IntPredicate imuPresent) {
        return trajectory(device, side, quietStanding, frontalTiltDeg, rotation, biasDps, imuPresent, CYCLES);
    }

    private static List<StoredPressureFrame> trajectory(UUID device, FootSide side, boolean quietStanding,
                                                        double frontalTiltDeg, double[][] rotation,
                                                        double[] biasDps, IntPredicate imuPresent, int cycles) {
        List<Integer> loaded = List.of(307, 307, 307, 307, 307, 307, 307, 307);
        List<Integer> unloaded = List.of(0, 0, 0, 0, 0, 0, 0, 0);
        double tilt = Math.toRadians(frontalTiltDeg);
        List<StoredPressureFrame> frames = new ArrayList<>();
        int index = 0;
        if (quietStanding) {
            for (int frame = 0; frame < STANDING_FRAMES; frame++) {
                frames.add(frame(device, side, index++, loaded, new double[] {0, 0, 1}, new double[3],
                        rotation, biasDps, imuPresent));
            }
        }
        for (int cycle = 0; cycle < cycles; cycle++) {
            for (int frame = 0; frame < SWING_FRAMES; frame++) {
                double sagittal = -SWING_PEAK_DPS * Math.sin(Math.PI * frame / SWING_FRAMES);
                frames.add(frame(device, side, index++, unloaded, new double[] {0.1, 0, 0.99},
                        new double[] {0, sagittal, 0}, rotation, biasDps, imuPresent));
            }
            for (int frame = 0; frame < STANCE_FRAMES; frame++) {
                frames.add(frame(device, side, index++, loaded, new double[] {0, Math.sin(tilt), Math.cos(tilt)},
                        new double[] {0, STANCE_SAGITTAL_DPS, STANCE_TRANSVERSE_DPS}, rotation, biasDps, imuPresent));
            }
        }
        return frames;
    }

    private static StoredPressureFrame frame(UUID device, FootSide side, int index, List<Integer> sensors,
                                             double[] accelG, double[] gyroDps, double[][] rotation,
                                             double[] biasDps, IntPredicate imuPresent) {
        long deviceTimeMs = (long) index * STEP_MS;
        Instant receiverTime = NOW.plusMillis(deviceTimeMs);
        if (!imuPresent.test(index)) {
            return new StoredPressureFrame(device, side, index, deviceTimeMs, NOW, sensors, 1, receiverTime,
                    DataMode.RAW, false, false, null, null, null);
        }
        double[] accel = multiply(rotation, accelG);
        double[] gyro = multiply(rotation, gyroDps);
        List<Integer> accelMg = new ArrayList<>(3);
        List<Integer> gyroDps10 = new ArrayList<>(3);
        for (int axis = 0; axis < 3; axis++) {
            accelMg.add((int) Math.round(accel[axis] * 1000.0));
            gyroDps10.add((int) Math.round((gyro[axis] + biasDps[axis]) * 10.0));
        }
        return new StoredPressureFrame(device, side, index, deviceTimeMs, NOW, sensors, 1, receiverTime,
                DataMode.RAW, false, true, accelMg, gyroDps10, 0);
    }

    private static double[] multiply(double[][] matrix, double[] vector) {
        double[] result = new double[3];
        for (int row = 0; row < 3; row++) {
            for (int column = 0; column < 3; column++) result[row] += matrix[row][column] * vector[column];
        }
        return result;
    }

    /** Rodrigues rotation matrix for a unit axis and an angle in radians. */
    private static double[][] rotation(double[] axis, double angle) {
        double c = Math.cos(angle), s = Math.sin(angle), t = 1 - c;
        double x = axis[0], y = axis[1], z = axis[2];
        return new double[][] {
                {t * x * x + c, t * x * y - s * z, t * x * z + s * y},
                {t * x * y + s * z, t * y * y + c, t * y * z - s * x},
                {t * x * z - s * y, t * y * z + s * x, t * z * z + c}};
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
}
