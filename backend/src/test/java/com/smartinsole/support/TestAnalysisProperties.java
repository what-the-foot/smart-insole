package com.smartinsole.support;

import com.smartinsole.global.config.AnalysisProperties;

/**
 * Builds {@link AnalysisProperties} for unit tests with the application.yml defaults so that adding a
 * threshold does not touch every test constructor.
 */
public final class TestAnalysisProperties {
    public static final String ALGORITHM_VERSION = "rule-v1.2.0";

    private TestAnalysisProperties() {
    }

    public static AnalysisProperties defaults() {
        return withAlgorithmVersion(ALGORITHM_VERSION);
    }

    public static AnalysisProperties withAlgorithmVersion(String algorithmVersion) {
        return withMinObservationWindows(algorithmVersion, 4);
    }

    public static AnalysisProperties withMinObservationWindows(int minObservationWindows) {
        return withMinObservationWindows(ALGORITHM_VERSION, minObservationWindows);
    }

    private static AnalysisProperties withMinObservationWindows(String algorithmVersion, int minObservationWindows) {
        return new AnalysisProperties(algorithmVersion, true, 4095, 30.0, 3.75,
                10.0, 0.60, 0.60, 0.60, 0.55, 5.0,
                0.20, 0.60, minObservationWindows, 60, 2);
    }
}
