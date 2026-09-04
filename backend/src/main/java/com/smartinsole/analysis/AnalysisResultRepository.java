package com.smartinsole.analysis;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AnalysisResultRepository extends JpaRepository<AnalysisResult, UUID> {
    Optional<AnalysisResult> findBySessionIdAndAlgorithmVersion(UUID sessionId, String algorithmVersion);
    Optional<AnalysisResult> findFirstBySessionIdOrderByCreatedAtDescIdDesc(UUID sessionId);
}
