package com.smartinsole.analysis;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisPatternRepository extends JpaRepository<AnalysisPattern, Long> {
    List<AnalysisPattern> findAllByAnalysisResultIdOrderBySortOrder(UUID analysisResultId);
}
