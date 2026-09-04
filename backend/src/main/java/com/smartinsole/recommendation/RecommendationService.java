package com.smartinsole.recommendation;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.recommendation.RecommendationDtos.RecommendationDetailResponse;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RecommendationService {
    private static final Map<String, List<String>> RELATED_PATTERNS = Map.of(
            "ANKLE_STABILITY_BASIC", List.of("LEFT_RIGHT_ASYMMETRY", "SHORT_CONTACT_TIME"),
            "BALANCED_FOOT_LOADING", List.of("MEDIAL_LOAD_TENDENCY", "LATERAL_LOAD_TENDENCY",
                    "HIGH_MIDFOOT_LOAD"),
            "REMEASURE_GUIDE", List.of("LOW_DATA_QUALITY")
    );
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
                    RELATED_PATTERNS.getOrDefault(recommendation.getCode(), List.of()));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored recommendation instructions are invalid", exception);
        }
    }
}
