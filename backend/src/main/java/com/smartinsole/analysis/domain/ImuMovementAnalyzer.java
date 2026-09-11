package com.smartinsole.analysis.domain;

import com.smartinsole.analysis.domain.RuleBasedAnalyzer.ContactWindow;
import com.smartinsole.analysis.dto.AnalysisDtos.MovementFootSummary;
import com.smartinsole.analysis.dto.AnalysisDtos.MovementReferenceMethod;
import com.smartinsole.analysis.dto.AnalysisDtos.MovementSummary;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.config.AnalysisProperties;
import com.smartinsole.measurement.repository.PressureFrameRepository.StoredPressureFrame;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * rule-v1.4.0 IMU stage (DEC-036). The LSM6DS3TR-C sits on the XIAO board strapped to the LATERAL
 * ankle/shank, not inside the insole, so everything computed here describes the SHANK segment; foot
 * inversion/eversion and the foot progression angle are not derivable and are never claimed. The mounting
 * orientation is undefined, so every session aligns the sensor axes automatically:
 *
 * <ol>
 *   <li>Units: g = mg / 1000, deg/s = dps10 / 10, dt from deviceTimeMs; int16-saturated samples are dropped.
 *       Each foot's frames arrive in the order of the receiver-unwrapped {@code sequence} (monotone across a
 *       device-time RESET, BLE_PROTOCOL §5), so a RESET shows up as deviceTimeMs going backwards: the contact
 *       window is closed there and the window integration state is reset.</li>
 *   <li>Reference posture per foot. QUIET_STANDING = the first run (from the foot's first frame) of at least
 *       {@code quietMinSeconds} where every frame is quiet (|gyro| below {@code quietGyroDpsMax},
 *       ||a| - 1 g| below {@code quietAccelDeviationGMax}) and the foot is in pressure contact; up = normalised
 *       mean accel, gyro bias = mean gyro. The method is session-level: QUIET_STANDING only when every foot
 *       with usable IMU samples found such a run, otherwise FIRST_STANCE for both feet = mid-stance frames of
 *       the first {@code fallbackWindowCount} contact windows, bias 0. The quiet-standing run is a contact
 *       window of its own and defines the reference, so it is excluded from the per-window metrics and from
 *       windowCount.</li>
 *   <li>Axis alignment per foot: e = principal eigenvector (PCA, Jacobi) of the bias-corrected gyro over all
 *       usable frames, made orthogonal to up and normalised; if the median of gyro.e over the swing frames
 *       (between consecutive contact windows) is positive, e = -e so that the forward swing is a negative
 *       rotation about e. ml_left = e, forward = ml_left x up; lateral = +ml_left for LEFT, -ml_left for
 *       RIGHT (the boards are mirrored on the lateral side). When a foot carries no rotation to align on (PCA
 *       axis null) or has no swing interval (fewer than two contact windows), the axis sign cannot be fixed:
 *       the foot reports windowCount 0 with null metrics while referenceMethod is kept.</li>
 *   <li>Per contact window: frontal tilt = atan2(g.lateral, g.up) of the normalised mean accel of the
 *       mid-stance frames; sagittal / transverse range = range of the cumulative trapezoid integral of
 *       gyro.ml_left / gyro.up inside the window. Swing peak = max |gyro.ml_left| between consecutive windows.</li>
 * </ol>
 *
 * Every threshold is a functional-test default without clinical validation. The class is pure and
 * deterministic; it never touches the raw frames.
 */
public final class ImuMovementAnalyzer {
    private static final double EPSILON = 1e-9;
    private static final int CONTIGUOUS_SAMPLE_PERIODS = 3;
    /** A foot whose reference exists but whose windows cannot be used (no axis, no swing, no usable window). */
    private static final MovementFootSummary NO_USABLE_WINDOWS = new MovementFootSummary(null, null, null, null, 0);

    private ImuMovementAnalyzer() {
    }

    /**
     * @param leftFrames  the left foot's stored frames in the exact order the contact windows index
     * @param leftWindows the left foot's contact windows (frame index ranges into {@code leftFrames})
     * @return the movement summary, or null when the session stored no frame with IMU data
     */
    public static MovementSummary analyze(List<StoredPressureFrame> leftFrames, List<ContactWindow> leftWindows,
                                          List<StoredPressureFrame> rightFrames, List<ContactWindow> rightWindows,
                                          int sampleRateHz, AnalysisProperties.Movement thresholds) {
        FootSamples left = FootSamples.of(leftFrames, leftWindows, thresholds);
        FootSamples right = FootSamples.of(rightFrames, rightWindows, thresholds);
        int frameCount = leftFrames.size() + rightFrames.size();
        int imuFrames = left.imuFrameCount() + right.imuFrameCount();
        if (frameCount == 0 || imuFrames == 0) return null;
        double coverage = (double) imuFrames / frameCount;
        if (coverage < thresholds.minImuCoverage()) {
            return new MovementSummary(coverage, null, null, null);
        }
        double samplePeriodMs = 1000.0 / sampleRateHz;
        Reference leftQuiet = quietStandingReference(left, samplePeriodMs, thresholds);
        Reference rightQuiet = quietStandingReference(right, samplePeriodMs, thresholds);
        boolean quiet = (leftQuiet != null || !left.hasUsable()) && (rightQuiet != null || !right.hasUsable());
        MovementReferenceMethod method = quiet ? MovementReferenceMethod.QUIET_STANDING
                : MovementReferenceMethod.FIRST_STANCE;
        Reference leftReference = quiet ? leftQuiet : firstStanceReference(left, thresholds);
        Reference rightReference = quiet ? rightQuiet : firstStanceReference(right, thresholds);
        MovementFootSummary leftSummary = leftReference == null ? null
                : footSummary(left, leftReference, FootSide.LEFT, thresholds);
        MovementFootSummary rightSummary = rightReference == null ? null
                : footSummary(right, rightReference, FootSide.RIGHT, thresholds);
        if (leftReference == null && rightReference == null) method = null;
        return new MovementSummary(coverage, method, leftSummary, rightSummary);
    }

    /**
     * First run of quiet, in-contact, usable frames whose duration (last - first + one sample period)
     * reaches the minimum; a device-time gap longer than three sample periods or any non-qualifying frame
     * ends the run. The reference carries the run's frame index range. Null when no run qualifies.
     */
    static Reference quietStandingReference(FootSamples foot, double samplePeriodMs,
                                            AnalysisProperties.Movement thresholds) {
        double minimumMs = thresholds.quietMinSeconds() * 1000.0;
        List<Sample> run = new ArrayList<>();
        int runStart = -1;
        List<Sample> samples = foot.samples();
        for (int index = 0; index < samples.size(); index++) {
            Sample sample = samples.get(index);
            boolean qualifies = sample.usable() && sample.contact()
                    && norm(sample.gyroDps()) < thresholds.quietGyroDpsMax()
                    && Math.abs(norm(sample.accelG()) - 1.0) < thresholds.quietAccelDeviationGMax();
            boolean contiguous = run.isEmpty() || (sample.deviceTimeMs() >= run.getLast().deviceTimeMs()
                    && sample.deviceTimeMs() - run.getLast().deviceTimeMs()
                    <= samplePeriodMs * CONTIGUOUS_SAMPLE_PERIODS);
            if (!qualifies || !contiguous) {
                Reference reference = referenceOfRun(run, runStart, samplePeriodMs, minimumMs);
                if (reference != null) return reference;
                run.clear();
                if (!qualifies) continue;
            }
            if (run.isEmpty()) runStart = index;
            run.add(sample);
        }
        return referenceOfRun(run, runStart, samplePeriodMs, minimumMs);
    }

    private static Reference referenceOfRun(List<Sample> run, int firstIndex, double samplePeriodMs,
                                            double minimumMs) {
        if (run.isEmpty()) return null;
        double durationMs = run.getLast().deviceTimeMs() - run.getFirst().deviceTimeMs() + samplePeriodMs;
        if (durationMs < minimumMs) return null;
        double[] up = normalized(mean(run, Sample::accelG));
        return up == null ? null
                : new Reference(up, mean(run, Sample::gyroDps), firstIndex, firstIndex + run.size() - 1);
    }

    /** Mid-stance usable frames of the first {@code fallbackWindowCount} windows; gyro bias 0. */
    static Reference firstStanceReference(FootSamples foot, AnalysisProperties.Movement thresholds) {
        List<Sample> pooled = new ArrayList<>();
        int windows = Math.min(thresholds.fallbackWindowCount(), foot.windows().size());
        for (int index = 0; index < windows; index++) {
            pooled.addAll(foot.midStance(foot.windows().get(index), thresholds));
        }
        if (pooled.isEmpty()) return null;
        double[] up = normalized(mean(pooled, Sample::accelG));
        return up == null ? null : new Reference(up, new double[3], -1, -1);
    }

    private static MovementFootSummary footSummary(FootSamples foot, Reference reference, FootSide side,
                                                   AnalysisProperties.Movement thresholds) {
        List<Sample> corrected = foot.biasCorrected(reference.gyroBias());
        double[] axis = principalAxis(covariance(corrected));
        if (axis == null) return NO_USABLE_WINDOWS;
        axis = orthogonalized(axis, reference.up());
        if (axis == null) return NO_USABLE_WINDOWS;
        List<Double> swingProjection = new ArrayList<>();
        for (List<Sample> swing : foot.swingIntervals(corrected)) {
            for (Sample sample : swing) swingProjection.add(dot(sample.gyroDps(), axis));
        }
        if (swingProjection.isEmpty()) return NO_USABLE_WINDOWS;
        if (median(swingProjection) > 0) axis = scaled(axis, -1);
        double[] mlLeft = axis;
        double[] lateral = side == FootSide.LEFT ? mlLeft : scaled(mlLeft, -1);
        double[] up = reference.up();

        List<Double> tilts = new ArrayList<>();
        List<Double> sagittal = new ArrayList<>();
        List<Double> transverse = new ArrayList<>();
        for (ContactWindow window : foot.windows()) {
            if (reference.covers(window)) continue;
            List<Sample> inside = usable(corrected, window.startIndex(), window.endIndex());
            List<Sample> mid = foot.midStance(corrected, window, thresholds);
            if (inside.size() < 2 || mid.isEmpty()) continue;
            double[] gravity = normalized(mean(mid, Sample::accelG));
            if (gravity == null) continue;
            tilts.add(Math.toDegrees(Math.atan2(dot(gravity, lateral), dot(gravity, up))));
            sagittal.add(integratedRange(inside, mlLeft));
            transverse.add(integratedRange(inside, up));
        }
        int windowCount = tilts.size();
        if (windowCount == 0) return NO_USABLE_WINDOWS;
        List<Double> swingPeaks = new ArrayList<>();
        for (List<Sample> swing : foot.swingIntervals(corrected)) {
            double peak = 0;
            for (Sample sample : swing) peak = Math.max(peak, Math.abs(dot(sample.gyroDps(), mlLeft)));
            swingPeaks.add(peak);
        }
        return new MovementFootSummary(mean(tilts), median(sagittal), median(transverse),
                swingPeaks.isEmpty() ? null : median(swingPeaks), windowCount);
    }

    /**
     * Range (max - min, degrees) of the angle series obtained by cumulative trapezoid integration of the
     * gyro component along {@code axis}; the integration state is reset when deviceTimeMs goes backwards.
     */
    static double integratedRange(List<Sample> samples, double[] axis) {
        double angle = 0, minimum = 0, maximum = 0;
        Sample previous = null;
        for (Sample sample : samples) {
            if (previous != null) {
                double dtSeconds = (sample.deviceTimeMs() - previous.deviceTimeMs()) / 1000.0;
                if (dtSeconds < 0) {
                    angle = 0;
                } else {
                    angle += (dot(previous.gyroDps(), axis) + dot(sample.gyroDps(), axis)) / 2.0 * dtSeconds;
                }
                minimum = Math.min(minimum, angle);
                maximum = Math.max(maximum, angle);
            }
            previous = sample;
        }
        return maximum - minimum;
    }

    private static List<Sample> usable(List<Sample> samples, int startIndex, int endIndex) {
        List<Sample> result = new ArrayList<>();
        for (int index = Math.max(0, startIndex); index <= endIndex && index < samples.size(); index++) {
            if (samples.get(index).usable()) result.add(samples.get(index));
        }
        return result;
    }

    /** Mean-centred 3x3 covariance of the gyro vectors of the usable samples (PCA input). */
    static double[][] covariance(List<Sample> samples) {
        List<Sample> usable = samples.stream().filter(Sample::usable).toList();
        double[][] matrix = new double[3][3];
        if (usable.size() < 2) return matrix;
        double[] mean = mean(usable, Sample::gyroDps);
        for (Sample sample : usable) {
            for (int row = 0; row < 3; row++) {
                for (int column = 0; column < 3; column++) {
                    matrix[row][column] += (sample.gyroDps()[row] - mean[row]) * (sample.gyroDps()[column] - mean[column]);
                }
            }
        }
        for (double[] row : matrix) for (int column = 0; column < 3; column++) row[column] /= usable.size() - 1;
        return matrix;
    }

    /**
     * Unit eigenvector of the largest eigenvalue of a symmetric 3x3 matrix (cyclic Jacobi rotations). Null
     * when the matrix has no positive eigenvalue, i.e. the samples carry no rotation to align on.
     */
    static double[] principalAxis(double[][] symmetric) {
        double[][] a = new double[3][3];
        double[][] v = new double[3][3];
        for (int row = 0; row < 3; row++) {
            a[row] = symmetric[row].clone();
            v[row][row] = 1;
        }
        for (int sweep = 0; sweep < 100; sweep++) {
            double off = a[0][1] * a[0][1] + a[0][2] * a[0][2] + a[1][2] * a[1][2];
            if (off < 1e-24) break;
            for (int p = 0; p < 2; p++) {
                for (int q = p + 1; q < 3; q++) {
                    if (Math.abs(a[p][q]) < 1e-300) continue;
                    double theta = (a[q][q] - a[p][p]) / (2 * a[p][q]);
                    double t = Math.signum(theta == 0 ? 1 : theta) / (Math.abs(theta) + Math.sqrt(theta * theta + 1));
                    double c = 1 / Math.sqrt(t * t + 1);
                    double s = t * c;
                    for (int k = 0; k < 3; k++) {
                        double akp = a[k][p], akq = a[k][q];
                        a[k][p] = c * akp - s * akq;
                        a[k][q] = s * akp + c * akq;
                    }
                    for (int k = 0; k < 3; k++) {
                        double apk = a[p][k], aqk = a[q][k];
                        a[p][k] = c * apk - s * aqk;
                        a[q][k] = s * apk + c * aqk;
                    }
                    for (int k = 0; k < 3; k++) {
                        double vkp = v[k][p], vkq = v[k][q];
                        v[k][p] = c * vkp - s * vkq;
                        v[k][q] = s * vkp + c * vkq;
                    }
                }
            }
        }
        int best = 0;
        for (int index = 1; index < 3; index++) if (a[index][index] > a[best][best]) best = index;
        if (a[best][best] <= EPSILON) return null;
        return normalized(new double[] {v[0][best], v[1][best], v[2][best]});
    }

    static double[] orthogonalized(double[] vector, double[] unit) {
        double projection = dot(vector, unit);
        return normalized(new double[] {vector[0] - projection * unit[0], vector[1] - projection * unit[1],
                vector[2] - projection * unit[2]});
    }

    static double[] normalized(double[] vector) {
        double length = norm(vector);
        return length < EPSILON ? null : scaled(vector, 1 / length);
    }

    private static double[] scaled(double[] vector, double factor) {
        return new double[] {vector[0] * factor, vector[1] * factor, vector[2] * factor};
    }

    static double dot(double[] left, double[] right) {
        return left[0] * right[0] + left[1] * right[1] + left[2] * right[2];
    }

    static double norm(double[] vector) {
        return Math.sqrt(dot(vector, vector));
    }

    private static double[] mean(List<Sample> samples, java.util.function.Function<Sample, double[]> component) {
        double[] sum = new double[3];
        for (Sample sample : samples) {
            double[] value = component.apply(sample);
            for (int axis = 0; axis < 3; axis++) sum[axis] += value[axis];
        }
        return samples.isEmpty() ? sum : scaled(sum, 1.0 / samples.size());
    }

    private static double mean(List<Double> values) {
        double sum = 0;
        for (double value : values) sum += value;
        return sum / values.size();
    }

    static double median(List<Double> values) {
        double[] sorted = values.stream().mapToDouble(Double::doubleValue).toArray();
        Arrays.sort(sorted);
        int middle = sorted.length / 2;
        return sorted.length % 2 == 1 ? sorted[middle] : (sorted[middle - 1] + sorted[middle]) / 2.0;
    }

    /** One frame's IMU sample in physical units; the vectors are zero when the frame carries none. */
    record Sample(long deviceTimeMs, boolean contact, boolean imuPresent, boolean usable,
                  double[] accelG, double[] gyroDps) {
        static Sample of(StoredPressureFrame frame, boolean contact, int saturationThreshold) {
            boolean present = Boolean.TRUE.equals(frame.imuAvailable()) && frame.accelMg() != null
                    && frame.gyroDps10() != null && frame.accelMg().size() == 3 && frame.gyroDps10().size() == 3;
            if (!present) return new Sample(frame.deviceTimeMs(), contact, false, false, new double[3], new double[3]);
            boolean saturated = false;
            for (int value : frame.accelMg()) saturated |= Math.abs(value) >= saturationThreshold;
            for (int value : frame.gyroDps10()) saturated |= Math.abs(value) >= saturationThreshold;
            double[] accel = new double[3];
            double[] gyro = new double[3];
            for (int axis = 0; axis < 3; axis++) {
                accel[axis] = frame.accelMg().get(axis) / 1000.0;
                gyro[axis] = frame.gyroDps10().get(axis) / 10.0;
            }
            return new Sample(frame.deviceTimeMs(), contact, true, !saturated, accel, gyro);
        }

        Sample withGyro(double[] gyro) {
            return new Sample(deviceTimeMs, contact, imuPresent, usable, accelG, gyro);
        }
    }

    /**
     * Reference posture of one foot's board: up in sensor coordinates, the gyro bias to subtract and the frame
     * index range of the quiet-standing run that defined it (-1/-1 for FIRST_STANCE).
     */
    record Reference(double[] up, double[] gyroBias, int firstIndex, int lastIndex) {
        /** True when the window shares at least one frame with the quiet-standing run. */
        boolean covers(ContactWindow window) {
            return firstIndex >= 0 && window.startIndex() <= lastIndex && window.endIndex() >= firstIndex;
        }
    }

    /** One foot's samples (frame order = contact window index order) plus its contact windows. */
    record FootSamples(List<Sample> samples, List<ContactWindow> windows) {
        static FootSamples of(List<StoredPressureFrame> frames, List<ContactWindow> windows,
                              AnalysisProperties.Movement thresholds) {
            boolean[] contact = new boolean[frames.size()];
            for (ContactWindow window : windows) {
                for (int index = window.startIndex(); index <= window.endIndex() && index < contact.length; index++) {
                    contact[index] = true;
                }
            }
            List<Sample> samples = new ArrayList<>(frames.size());
            for (int index = 0; index < frames.size(); index++) {
                samples.add(Sample.of(frames.get(index), contact[index], thresholds.saturationThreshold()));
            }
            return new FootSamples(List.copyOf(samples), List.copyOf(windows));
        }

        int imuFrameCount() {
            return (int) samples.stream().filter(Sample::imuPresent).count();
        }

        boolean hasUsable() {
            return samples.stream().anyMatch(Sample::usable);
        }

        List<Sample> biasCorrected(double[] bias) {
            List<Sample> corrected = new ArrayList<>(samples.size());
            for (Sample sample : samples) {
                corrected.add(sample.withGyro(new double[] {sample.gyroDps()[0] - bias[0],
                        sample.gyroDps()[1] - bias[1], sample.gyroDps()[2] - bias[2]}));
            }
            return corrected;
        }

        List<Sample> midStance(ContactWindow window, AnalysisProperties.Movement thresholds) {
            return midStance(samples, window, thresholds);
        }

        /** Usable samples inside the 30-60 % (configurable) part of the window's frame range. */
        List<Sample> midStance(List<Sample> source, ContactWindow window, AnalysisProperties.Movement thresholds) {
            int count = window.endIndex() - window.startIndex() + 1;
            int from = window.startIndex() + (int) Math.floor(thresholds.midStanceStartFraction() * count);
            int to = window.startIndex() + (int) Math.ceil(thresholds.midStanceEndFraction() * count) - 1;
            to = Math.min(window.endIndex(), Math.max(from, to));
            return usable(source, from, to);
        }

        /** Usable samples strictly between consecutive contact windows (the swing of this foot). */
        List<List<Sample>> swingIntervals(List<Sample> source) {
            List<List<Sample>> intervals = new ArrayList<>();
            for (int index = 1; index < windows.size(); index++) {
                List<Sample> swing = usable(source, windows.get(index - 1).endIndex() + 1,
                        windows.get(index).startIndex() - 1);
                if (!swing.isEmpty()) intervals.add(swing);
            }
            return intervals;
        }
    }
}
