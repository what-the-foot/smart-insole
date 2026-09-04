package com.smartinsole.analysis;

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
    public record GaitSummary(double cadence, double leftContactTimeMs, double rightContactTimeMs,
                              double symmetryIndex, Integer validStepCount) { }
    public record CenterOfPressure(double x, double y) { }
    /**
     * Stored as JSON on the result. Fields added after rule-v1.0.0 are nullable so results persisted by
     * older versions are returned with null instead of a fabricated 0. The sensor share arrays
     * (rule-v1.2.0) are the contact-frame mean of sensor / total x 100 in layout index order.
     */
    public record PressureDistribution(double leftMedialRatio, double leftLateralRatio,
                                       double rightMedialRatio, double rightLateralRatio,
                                       double leftHeelRatio, double rightHeelRatio,
                                       Double leftMidfootRatio, Double rightMidfootRatio,
                                       Double leftForefootRatio, Double rightForefootRatio,
                                       Double leftPeakPressure, Double rightPeakPressure,
                                       CenterOfPressure leftMeanCoP, CenterOfPressure rightMeanCoP,
                                       List<Double> leftSensorSharePct, List<Double> rightSensorSharePct) { }
    /** Observation fields are null for results stored before rule-v1.2.0. */
    public record PatternResult(String code, PatternSeverity severity, String title, String message,
                                String evidence, ObservationLevel observationLevel, Double occurrenceRate,
                                Integer observedCount, Integer windowCount) { }
    /** One of the six rule-v1.2.0 codes with its level over the valid-step windows of the session. */
    public record ObservationSummaryItem(String code, ObservationLevel observationLevel, double occurrenceRate,
                                         int observedCount, int windowCount) { }
    public record RecommendationSummary(String code, String title, String summary, int durationMinutes) { }
    public record AnalysisResultResponse(
            UUID sessionId,
            String status,
            String algorithmVersion,
            DataQualityResult dataQuality,
            GaitSummary gaitSummary,
            PressureDistribution pressureDistribution,
            List<PatternResult> patterns,
            List<ObservationSummaryItem> observationSummary,
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
            PressureDistribution distribution,
            List<ComputedPattern> patterns,
            List<ObservationSummaryItem> observationSummary,
            List<String> recommendationCodes
    ) { }

    public record ComputedPattern(String code, PatternSeverity severity, String title, String message,
                                  String evidence, ObservationLevel observationLevel, double occurrenceRate,
                                  int observedCount, int windowCount) { }
}
