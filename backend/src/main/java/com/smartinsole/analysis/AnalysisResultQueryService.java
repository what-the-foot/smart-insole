package com.smartinsole.analysis;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.AnalysisDtos.AnalysisPendingResponse;
import com.smartinsole.analysis.AnalysisDtos.AnalysisResultResponse;
import com.smartinsole.analysis.AnalysisDtos.DataQualityResult;
import com.smartinsole.analysis.AnalysisDtos.GaitSummary;
import com.smartinsole.analysis.AnalysisDtos.ObservationSummaryItem;
import com.smartinsole.analysis.AnalysisDtos.PatternResult;
import com.smartinsole.analysis.AnalysisDtos.PressureDistribution;
import com.smartinsole.analysis.AnalysisDtos.RecommendationSummary;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.MeasurementSession;
import com.smartinsole.measurement.MeasurementSessionRepository;
import com.smartinsole.recommendation.Recommendation;
import com.smartinsole.recommendation.RecommendationRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AnalysisResultQueryService {
    public static final String DISCLAIMER =
            "본 결과는 의료 진단이 아니며, 통증이 지속되면 전문가의 평가가 필요합니다.";
    private final MeasurementSessionRepository sessions;
    private final AnalysisResultRepository results;
    private final AnalysisPatternRepository patterns;
    private final RecommendationRepository recommendations;
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public AnalysisResultQueryService(MeasurementSessionRepository sessions, AnalysisResultRepository results,
                                      AnalysisPatternRepository patterns,
                                      RecommendationRepository recommendations,
                                      JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.sessions = sessions;
        this.results = results;
        this.patterns = patterns;
        this.recommendations = recommendations;
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public ResponseEntity<Object> result(UUID sessionId, UUID userId) {
        MeasurementSession session = sessions.findByIdAndUserId(sessionId, userId).orElseThrow(() ->
                sessions.existsById(sessionId)
                        ? new BusinessException(ErrorCode.ACCESS_DENIED)
                        : new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (session.getStatus() == MeasurementStatus.PROCESSING) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).body(new AnalysisPendingResponse(sessionId,
                    "PROCESSING", "분석이 진행 중입니다."));
        }
        if (session.getStatus() == MeasurementStatus.FAILED) {
            throw new BusinessException(ErrorCode.ANALYSIS_FAILED);
        }
        if (session.getStatus() != MeasurementStatus.COMPLETED) {
            throw new BusinessException(ErrorCode.INVALID_SESSION_STATE,
                    ErrorCode.INVALID_SESSION_STATE.defaultMessage(),
                    Map.of("currentStatus", session.getStatus().name()));
        }
        AnalysisResult result = results.findFirstBySessionIdOrderByCreatedAtDescIdDesc(sessionId)
                .orElseThrow(() -> new IllegalStateException("Completed session has no analysis result"));
        List<PatternResult> patternResponses = patterns.findAllByAnalysisResultIdOrderBySortOrder(result.getId())
                .stream().map(pattern -> new PatternResult(pattern.getPatternCode(), pattern.getSeverity(),
                        pattern.getTitle(), pattern.getMessage(), pattern.getEvidence(),
                        pattern.getObservationLevel(), pattern.getOccurrenceRate(), pattern.getObservedCount(),
                        pattern.getWindowCount())).toList();
        // Results stored before rule-v1.2.0 have no summary; they are returned as null, never re-derived.
        List<ObservationSummaryItem> observationSummary = result.getObservationSummaryJson() == null ? null
                : parse(result.getObservationSummaryJson(), new TypeReference<>() { });
        List<String> recommendationCodes = jdbcTemplate.queryForList("""
                        SELECT recommendation_code FROM result_recommendations
                        WHERE analysis_result_id = ? ORDER BY sort_order
                        """, String.class, result.getId().toString());
        Map<String, Recommendation> guides = new HashMap<>();
        recommendations.findAllById(recommendationCodes).forEach(guide -> guides.put(guide.getCode(), guide));
        List<RecommendationSummary> recommendationResponses = recommendationCodes.stream()
                .map(guides::get).filter(java.util.Objects::nonNull)
                .map(guide -> new RecommendationSummary(guide.getCode(), guide.getTitle(), guide.getSummary(),
                        guide.getDurationMinutes())).toList();
        return ResponseEntity.ok(new AnalysisResultResponse(sessionId, "COMPLETED", result.getAlgorithmVersion(),
                new DataQualityResult(result.getQualityScore(), result.getQualityLevel(),
                        result.getMissingFrameRate(), parse(result.getQualityFlagsJson(), new TypeReference<>() { })),
                new GaitSummary(result.getCadence(), result.getLeftContactTimeMs(),
                        result.getRightContactTimeMs(), result.getSymmetryIndex(), result.getValidStepCount()),
                parse(result.getPressureDistributionJson(), new TypeReference<>() { }), patternResponses,
                observationSummary, recommendationResponses, DISCLAIMER, result.getCreatedAt()));
    }

    private <T> T parse(String json, TypeReference<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored analysis JSON is invalid", exception);
        }
    }
}
