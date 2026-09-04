package com.smartinsole.global.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("app.analysis")
public record AnalysisProperties(
        String algorithmVersion,
        boolean defaultsAreFunctionalTestValues,
        double contactTotalThreshold,
        double asymmetryThresholdPercent,
        double medialRatioThreshold,
        double lateralRatioThreshold,
        double midfootRatioThreshold,
        double shortContactTimeMs,
        int poorQualityScoreThreshold,
        int maxAttempts
) {
    public AnalysisProperties {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("app.analysis.max-attempts must be between 1 and 5");
        }
    }
}
