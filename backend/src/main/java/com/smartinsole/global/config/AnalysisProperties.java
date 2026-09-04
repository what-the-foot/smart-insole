package com.smartinsole.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Rule-based analysis thresholds. All values are functional-test defaults, not clinically validated.
 *
 * @param adcMaxValue                    fallback ADC scale when a session has no snapshot (sessions
 *                                       created since V5 always carry their own adcMax)
 * @param contactTotalThreshold          contact threshold on the sum of the normalised (0..100 per
 *                                       sensor) values of one frame; used when the per-sensor value is
 *                                       absent
 * @param contactTotalThresholdPerSensor optional per-sensor contact threshold (normalised units); the
 *                                       effective frame threshold is this value times sensorCount
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
        double midfootRatioThreshold,
        double shortContactTimeMs,
        int poorQualityScoreThreshold,
        int maxAttempts
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
        if (poorQualityScoreThreshold < 0 || poorQualityScoreThreshold > 100) {
            throw new IllegalArgumentException("app.analysis.poor-quality-score-threshold must be 0..100");
        }
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("app.analysis.max-attempts must be between 1 and 5");
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
}
