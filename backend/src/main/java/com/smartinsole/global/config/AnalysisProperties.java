package com.smartinsole.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rule-based analysis thresholds (rule-v1.2.0 pressure rules plus the rule-v1.4.0 IMU shank movement
 * stage in {@link Movement}). All values are functional-test defaults, not clinically validated.
 *
 * @param adcMaxValue                    fallback ADC scale when a session has no snapshot (sessions
 *                                       created since V5 always carry their own adcMax)
 * @param contactTotalThreshold          contact threshold on the sum of the normalised (0..100 per
 *                                       sensor) values of one frame; used when the per-sensor value is
 *                                       absent
 * @param contactTotalThresholdPerSensor optional per-sensor contact threshold (normalised units); the
 *                                       effective frame threshold is this value times sensorCount
 * @param asymmetryThresholdPercent      per step pair: |left - right| / mean contact duration, in %
 * @param medialRatioThreshold           per window: medial / (medial + lateral)
 * @param lateralRatioThreshold          per window: lateral / (medial + lateral)
 * @param forefootRatioThreshold         per window: (FOREFOOT + TOE) / total
 * @param rearfootRatioThreshold         per window: HEEL / total
 * @param halluxSharePctThreshold        per window: TOE-MEDIAL sensor share (%) below which the hallux
 *                                       signal counts as low
 * @param partialObservationRate         occurrence rate from which a pattern is PARTIALLY_OBSERVED
 * @param repeatedObservationRate        occurrence rate from which a pattern is REPEATEDLY_OBSERVED
 * @param minObservationWindows          fewer windows than this keeps every pattern NOT_OBSERVED
 * @param poorQualityScoreThreshold      quality score below which LOW_DATA_QUALITY is flagged and
 *                                       REMEASURE_GUIDE is recommended
 * @param movement                       rule-v1.4.0 IMU shank movement thresholds (defaults when absent)
 */
@ConfigurationProperties("app.analysis")
public record AnalysisProperties(
        String algorithmVersion,
        boolean defaultsAreFunctionalTestValues,
        int adcMaxValue,
        double contactTotalThreshold,
        Double contactTotalThresholdPerSensor,
        double asymmetryThresholdPercent,
        double medialRatioThreshold,
        double lateralRatioThreshold,
        double forefootRatioThreshold,
        double rearfootRatioThreshold,
        double halluxSharePctThreshold,
        double partialObservationRate,
        double repeatedObservationRate,
        int minObservationWindows,
        int poorQualityScoreThreshold,
        int maxAttempts,
        Movement movement
) {
    private static final int MAX_SENSORS = 8;
    private static final double MAX_NORMALIZED_VALUE = 100.0;

    public AnalysisProperties {
        if (algorithmVersion == null || algorithmVersion.isBlank()) {
            throw new IllegalArgumentException("app.analysis.algorithm-version must not be blank");
        }
        if (adcMaxValue < 1) {
            throw new IllegalArgumentException("app.analysis.adc-max-value must be positive");
        }
        if (contactTotalThreshold <= 0 || contactTotalThreshold > MAX_NORMALIZED_VALUE * MAX_SENSORS) {
            throw new IllegalArgumentException(
                    "app.analysis.contact-total-threshold must be within (0, 800] normalised units");
        }
        if (contactTotalThresholdPerSensor != null
                && (contactTotalThresholdPerSensor <= 0 || contactTotalThresholdPerSensor > MAX_NORMALIZED_VALUE)) {
            throw new IllegalArgumentException(
                    "app.analysis.contact-total-threshold-per-sensor must be within (0, 100] normalised units");
        }
        requireRatio("asymmetry-threshold-percent", asymmetryThresholdPercent, 100.0);
        requireRatio("medial-ratio-threshold", medialRatioThreshold, 1.0);
        requireRatio("lateral-ratio-threshold", lateralRatioThreshold, 1.0);
        requireRatio("forefoot-ratio-threshold", forefootRatioThreshold, 1.0);
        requireRatio("rearfoot-ratio-threshold", rearfootRatioThreshold, 1.0);
        requireRatio("hallux-share-pct-threshold", halluxSharePctThreshold, 100.0);
        requireRatio("partial-observation-rate", partialObservationRate, 1.0);
        requireRatio("repeated-observation-rate", repeatedObservationRate, 1.0);
        if (repeatedObservationRate < partialObservationRate) {
            throw new IllegalArgumentException(
                    "app.analysis.repeated-observation-rate must not be below partial-observation-rate");
        }
        if (minObservationWindows < 1) {
            throw new IllegalArgumentException("app.analysis.min-observation-windows must be at least 1");
        }
        if (poorQualityScoreThreshold < 0 || poorQualityScoreThreshold > 100) {
            throw new IllegalArgumentException("app.analysis.poor-quality-score-threshold must be 0..100");
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("app.analysis.max-attempts must be between 1 and 5");
        }
        if (movement == null) {
            movement = Movement.defaults();
        }
    }

    private static void requireRatio(String name, double value, double maximum) {
        if (value <= 0 || value > maximum) {
            throw new IllegalArgumentException("app.analysis." + name + " must be within (0, " + maximum + "]");
        }
    }

    /** Frame contact threshold for a foot with the given sensor count, in normalised (0..100) units. */
    public double contactThreshold(int sensorCount) {
        if (contactTotalThresholdPerSensor != null) {
            return contactTotalThresholdPerSensor * sensorCount;
        }
        return contactTotalThreshold;
    }

    /** Session ADC scale, falling back to the configured default when the session carries none. */
    public int adcMaxFor(int sessionAdcMax) {
        return sessionAdcMax > 0 ? sessionAdcMax : adcMaxValue;
    }

    /**
     * rule-v1.4.0 IMU shank movement stage (DEC-036). The board sits on the lateral ankle/shank, so every
     * value describes the shank segment; all thresholds are functional-test defaults without clinical
     * validation.
     *
     * @param quietMinSeconds         minimum length of the quiet-standing reference interval
     * @param quietGyroDpsMax         every reference frame must have |gyro| below this (deg/s)
     * @param quietAccelDeviationGMax every reference frame must have ||a| - 1 g| below this (g)
     * @param minImuCoverage          left/right summaries are null below this share of IMU frames
     * @param midStanceStartFraction  start of the mid-stance part of a contact window (fraction of frames)
     * @param midStanceEndFraction    end of the mid-stance part of a contact window (fraction of frames)
     * @param saturationThreshold     int16 magnitude from which an IMU sample counts as saturated
     * @param fallbackWindowCount     contact windows averaged for the FIRST_STANCE reference
     */
    public record Movement(
            double quietMinSeconds,
            double quietGyroDpsMax,
            double quietAccelDeviationGMax,
            double minImuCoverage,
            double midStanceStartFraction,
            double midStanceEndFraction,
            int saturationThreshold,
            int fallbackWindowCount
    ) {
        public Movement {
            if (quietMinSeconds <= 0 || quietMinSeconds > 60) {
                throw new IllegalArgumentException("app.analysis.movement.quiet-min-seconds must be within (0, 60]");
            }
            if (quietGyroDpsMax <= 0 || quietGyroDpsMax > 500) {
                throw new IllegalArgumentException("app.analysis.movement.quiet-gyro-dps-max must be within (0, 500]");
            }
            if (quietAccelDeviationGMax <= 0 || quietAccelDeviationGMax > 1) {
                throw new IllegalArgumentException(
                        "app.analysis.movement.quiet-accel-deviation-g-max must be within (0, 1]");
            }
            if (minImuCoverage < 0 || minImuCoverage > 1) {
                throw new IllegalArgumentException("app.analysis.movement.min-imu-coverage must be within [0, 1]");
            }
            if (midStanceStartFraction < 0 || midStanceEndFraction > 1 || midStanceStartFraction >= midStanceEndFraction) {
                throw new IllegalArgumentException(
                        "app.analysis.movement mid-stance fractions must satisfy 0 <= start < end <= 1");
            }
            if (saturationThreshold < 1 || saturationThreshold > 32767) {
                throw new IllegalArgumentException(
                        "app.analysis.movement.saturation-threshold must be within [1, 32767]");
            }
            if (fallbackWindowCount < 1) {
                throw new IllegalArgumentException("app.analysis.movement.fallback-window-count must be at least 1");
            }
        }

        /** application.yml defaults: 1.0 s, 10 dps, 0.1 g, coverage 0.5, mid-stance 30-60 %, 32760, 3 windows. */
        public static Movement defaults() {
            return new Movement(1.0, 10.0, 0.1, 0.5, 0.30, 0.60, 32760, 3);
        }
    }
}
