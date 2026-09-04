package com.smartinsole.recommendation;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RecommendationRepository extends JpaRepository<Recommendation, String> {
    List<Recommendation> findAllByCodeInAndActiveTrue(List<String> codes);
}
