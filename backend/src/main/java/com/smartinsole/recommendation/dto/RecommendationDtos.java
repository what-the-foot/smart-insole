package com.smartinsole.recommendation.dto;

import java.util.List;

public final class RecommendationDtos {
    private RecommendationDtos() {
    }

    public record RecommendationDetailResponse(
            String code,
            String title,
            String purpose,
            List<String> instructions,
            int durationMinutes,
            String cautionText,
            List<String> relatedPatternCodes
    ) {
    }
}
