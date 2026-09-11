package com.smartinsole.analysis.dto;

import com.smartinsole.global.common.DomainTypes.ObservationLevel;
import com.smartinsole.global.common.DomainTypes.PatternSeverity;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class AnalysisDtos {
    private AnalysisDtos() {
    }

    public record AnalysisPendingResponse(UUID sessionId, String status, String message) { }
    public record DataQualityResult(int score, QualityLevel level, double missingFrameRate,
                                    List<String> flags) { }
    /**
     * Stride fields (rule-v1.3.0) are the median start-to-start interval of one foot's consecutive contact
     * windows in device time; null with fewer than two windows and for results stored by earlier versions.
     */
    public record GaitSummary(double cadence, double leftContactTimeMs, double rightContactTimeMs,
                              double symmetryIndex, Integer validStepCount,
                              Double leftStrideTimeMs, Double rightStrideTimeMs, Double meanStrideTimeMs) {
        /** Legacy shape without stride times (results stored before rule-v1.3.0). */
        public GaitSummary(double cadence, double leftContactTimeMs, double rightContactTimeMs,
                           double symmetryIndex, Integer validStepCount) {
            this(cadence, leftContactTimeMs, rightContactTimeMs, symmetryIndex, validStepCount, null, null, null);
        }
    }
    public record CenterOfPressure(double x, double y) { }
    /**
     * Stored as JSON on the result. Fields added after rule-v1.0.0 are nullable so results persisted by
     * older versions are returned with null instead of a fabricated 0. The sensor share arrays
     * (rule-v1.2.0) are the contact-frame mean of sensor / total x 100 in layout index order. The load share
     * pair (rule-v1.3.0) is each foot's mean contact-frame total as a share of both feet (L/(L+R) x 100), a
     * relative signal share rather than force or weight; null when either foot has no contact window.
     */
    public record PressureDistribution(double leftMedialRatio, double leftLateralRatio,
                                       double rightMedialRatio, double rightLateralRatio,
                                       double leftHeelRatio, double rightHeelRatio,
                                       Double leftMidfootRatio, Double rightMidfootRatio,
                                       Double leftForefootRatio, Double rightForefootRatio,
                                       Double leftPeakPressure, Double rightPeakPressure,
                                       CenterOfPressure leftMeanCoP, CenterOfPressure rightMeanCoP,
                                       List<Double> leftSensorSharePct, List<Double> rightSensorSharePct,
                                       Double leftLoadSharePct, Double rightLoadSharePct) { }
    /** Observation fields are null for results stored before rule-v1.2.0. */
    public record PatternResult(String code, PatternSeverity severity, String title, String message,
                                String evidence, ObservationLevel observationLevel, Double occurrenceRate,
                                Integer observedCount, Integer windowCount) { }
    /** One of the six rule-v1.2.0 codes with its level over the valid-step windows of the session. */
    public record ObservationSummaryItem(String code, ObservationLevel observationLevel, double occurrenceRate,
                                         int observedCount, int windowCount) { }
    public record RecommendationSummary(String code, String title, String summary, int durationMinutes) { }
    /** rule-v1.4.0: how the shank reference posture of a session was established (DEC-036). */
    public enum MovementReferenceMethod { QUIET_STANDING, FIRST_STANCE }
    /**
     * rule-v1.4.0 shank movement summary of one foot's board (the IMU is strapped to the lateral ankle/shank,
     * not inside the insole, so nothing here is a foot joint angle). The four metrics are null when
     * {@code windowCount} is 0; {@code windowCount} counts the contact windows with usable IMU samples.
     */
    public record MovementFootSummary(Double frontalTiltDeg, Double sagittalRangeDeg, Double transverseRangeDeg,
                                      Double swingPeakAngularVelocityDps, int windowCount) { }
    /**
     * rule-v1.4.0 IMU shank movement summary, stored as one JSON document (V9). The whole object is null
     * for results stored before rule-v1.4.0 and for sessions without IMU frames; left/right are null
     * when {@code imuCoverage} is below the configured minimum or the reference posture could not be
     * established ({@code referenceMethod} null).
     */
    public record MovementSummary(double imuCoverage, MovementReferenceMethod referenceMethod,
                                  MovementFootSummary left, MovementFootSummary right) { }
    public record AnalysisResultResponse(
            UUID sessionId,
            String status,
            String algorithmVersion,
            DataQualityResult dataQuality,
            GaitSummary gaitSummary,
            PressureDistribution pressureDistribution,
            List<PatternResult> patterns,
            List<ObservationSummaryItem> observationSummary,
            MovementSummary movementSummary,
            List<RecommendationSummary> recommendations,
            String disclaimer,
            Instant createdAt
    ) { }

    public record ComputedAnalysis(
            int qualityScore,
            QualityLevel qualityLevel,
            double missingFrameRate,
            List<String> qualityFlags,
            double cadence,
            double leftContactTimeMs,
            double rightContactTimeMs,
            double symmetryIndex,
            int validStepCount,
            Double leftStrideTimeMs,
            Double rightStrideTimeMs,
            Double meanStrideTimeMs,
            PressureDistribution distribution,
            List<ComputedPattern> patterns,
            List<ObservationSummaryItem> observationSummary,
            List<String> recommendationCodes,
            MovementSummary movementSummary
    ) { }

    public record ComputedPattern(String code, PatternSeverity severity, String title, String message,
                                  String evidence, ObservationLevel observationLevel, double occurrenceRate,
                                  int observedCount, int windowCount) { }
}
