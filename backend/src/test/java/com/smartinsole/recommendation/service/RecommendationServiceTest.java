package com.smartinsole.recommendation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.analysis.domain.PatternCatalog;
import com.smartinsole.recommendation.domain.Recommendation;
import com.smartinsole.recommendation.dto.RecommendationDtos.RecommendationDetailResponse;
import com.smartinsole.recommendation.repository.RecommendationRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class RecommendationServiceTest {
    private final RecommendationRepository recommendations = mock(RecommendationRepository.class);
    private final RecommendationService service = new RecommendationService(recommendations, new ObjectMapper());

    @Test
    void relatedPatternCodesAreTheRuleV120VocabularyOnly() {
        for (String code : List.of("ANKLE_STABILITY_BASIC", "BALANCED_FOOT_LOADING", "REMEASURE_GUIDE")) {
            when(recommendations.findById(code)).thenReturn(Optional.of(new Recommendation(code, code, code,
                    "[\"step\"]", 5, "caution", true)));
        }

        RecommendationDetailResponse ankle = service.get("ANKLE_STABILITY_BASIC");
        RecommendationDetailResponse loading = service.get("BALANCED_FOOT_LOADING");
        RecommendationDetailResponse remeasure = service.get("REMEASURE_GUIDE");

        assertThat(ankle.relatedPatternCodes()).containsExactly("LEFT_RIGHT_ASYMMETRY");
        assertThat(loading.relatedPatternCodes()).containsExactly("MEDIAL_LOAD_TENDENCY", "LATERAL_LOAD_TENDENCY",
                "LOW_HALLUX_SIGNAL", "FOREFOOT_LOAD_TENDENCY", "REARFOOT_LOAD_TENDENCY");
        assertThat(remeasure.relatedPatternCodes()).isEmpty();
        List<String> all = new java.util.ArrayList<>(ankle.relatedPatternCodes());
        all.addAll(loading.relatedPatternCodes());
        assertThat(all).containsExactlyInAnyOrderElementsOf(PatternCatalog.CODES)
                .doesNotContain("HIGH_MIDFOOT_LOAD", "SHORT_CONTACT_TIME", "LOW_DATA_QUALITY");
    }
}
