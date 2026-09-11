package com.smartinsole.recommendation.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.domain.PatternCatalog;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.recommendation.domain.Recommendation;
import com.smartinsole.recommendation.dto.RecommendationDtos.RecommendationDetailResponse;
import com.smartinsole.recommendation.repository.RecommendationRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {
    private final RecommendationRepository recommendations;
    private final ObjectMapper objectMapper;

    public RecommendationService(RecommendationRepository recommendations, ObjectMapper objectMapper) {
        this.recommendations = recommendations;
        this.objectMapper = objectMapper;
    }

    @Transactional(readOnly = true)
    public RecommendationDetailResponse get(String code) {
        Recommendation recommendation = recommendations.findById(code)
                .filter(Recommendation::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        try {
            List<String> instructions = objectMapper.readValue(recommendation.getInstructionsJson(),
                    new TypeReference<List<String>>() { });
            return new RecommendationDetailResponse(recommendation.getCode(), recommendation.getTitle(),
                    recommendation.getSummary(), List.copyOf(instructions), recommendation.getDurationMinutes(),
                    recommendation.getCautionText(),
                    // rule-v1.2.0 mapping; REMEASURE_GUIDE is driven by the quality score, so it has none.
                    PatternCatalog.patternsFor(recommendation.getCode()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored recommendation instructions are invalid", exception);
        }
    }
}
