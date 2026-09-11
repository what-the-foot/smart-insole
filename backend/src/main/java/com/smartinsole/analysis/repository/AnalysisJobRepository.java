package com.smartinsole.analysis.repository;

import com.smartinsole.analysis.domain.AnalysisJob;
import com.smartinsole.global.common.DomainTypes.AnalysisJobStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AnalysisJobRepository extends JpaRepository<AnalysisJob, UUID> {
    Optional<AnalysisJob> findBySessionIdAndAlgorithmVersion(UUID sessionId, String algorithmVersion);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT job FROM AnalysisJob job WHERE job.sessionId = :sessionId "
            + "AND job.algorithmVersion = :algorithmVersion")
    Optional<AnalysisJob> findForUpdate(@Param("sessionId") UUID sessionId,
                                        @Param("algorithmVersion") String algorithmVersion);

    List<AnalysisJob> findAllByStatusIn(Collection<AnalysisJobStatus> statuses);
    List<AnalysisJob> findAllByStatusAndAlgorithmVersion(AnalysisJobStatus status, String algorithmVersion);
}
