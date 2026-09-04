package com.smartinsole.analysis;

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
    public record PressureDistribution(double leftMedialRatio, double leftLateralRatio,
                                       double rightMedialRatio, double rightLateralRatio,
                                       double leftHeelRatio, double rightHeelRatio,
                                       Double leftMidfootRatio, Double rightMidfootRatio,
                                       Double leftForefootRatio, Double rightForefootRatio,
                                       Double leftPeakPressure, Double rightPeakPressure,
                                       CenterOfPressure leftMeanCoP, CenterOfPressure rightMeanCoP) { }
    public record PatternResult(String code, PatternSeverity severity, String title, String message,
                                String evidence) { }
    public record RecommendationSummary(String code, String title, String summary, int durationMinutes) { }
    public record AnalysisResultResponse(
            UUID sessionId,
            String status,
            String algorithmVersion,
            DataQualityResult dataQuality,
            GaitSummary gaitSummary,
            PressureDistribution pressureDistribution,
            List<PatternResult> patterns,
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
            double leftMidfootRatio,
            double rightMidfootRatio,
            List<ComputedPattern> patterns,
            List<String> recommendationCodes
    ) { }

    public record ComputedPattern(String code, PatternSeverity severity, String title, String message,
                                  String evidence) { }
}
