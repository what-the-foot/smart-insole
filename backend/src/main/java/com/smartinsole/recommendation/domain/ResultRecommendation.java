package com.smartinsole.recommendation.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "result_recommendations")
@IdClass(ResultRecommendation.ResultRecommendationId.class)
class ResultRecommendation {
    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "analysis_result_id", length = 36)
    private UUID analysisResultId;
    @Id
    @Column(name = "recommendation_code", length = 100)
    private String recommendationCode;
    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    public static class ResultRecommendationId implements Serializable {
        private UUID analysisResultId;
        private String recommendationCode;

        public ResultRecommendationId() { }

        @Override
        public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof ResultRecommendationId that)) return false;
            return Objects.equals(analysisResultId, that.analysisResultId)
                    && Objects.equals(recommendationCode, that.recommendationCode);
        }

        @Override
        public int hashCode() {
            return Objects.hash(analysisResultId, recommendationCode);
        }
    }
}
