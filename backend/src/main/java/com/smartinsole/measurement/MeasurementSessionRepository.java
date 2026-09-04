package com.smartinsole.measurement;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MeasurementSessionRepository extends JpaRepository<MeasurementSession, UUID> {
    Optional<MeasurementSession> findByIdAndUserId(UUID id, UUID userId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT session FROM MeasurementSession session WHERE session.id = :id")
    Optional<MeasurementSession> findByIdForUpdate(@Param("id") UUID id);

    Page<MeasurementSession> findAllByUserId(UUID userId, Pageable pageable);
    Page<MeasurementSession> findAllByUserIdAndStatus(UUID userId, MeasurementStatus status, Pageable pageable);
    List<MeasurementSession> findAllByStatus(MeasurementStatus status);

    @Query(value = """
            SELECT DISTINCT s.* FROM measurement_sessions s
            WHERE s.user_id = :userId
              AND (:status IS NULL OR s.status = :status)
              AND (:fromTime IS NULL OR s.created_at >= :fromTime)
              AND (:toTime IS NULL OR s.created_at <= :toTime)
              AND (:minQualityScore IS NULL OR s.data_quality_score >= :minQualityScore)
              AND (:patternCode IS NULL OR EXISTS (
                    SELECT 1 FROM analysis_results ar
                    JOIN analysis_patterns ap ON ap.analysis_result_id = ar.id
                    WHERE ar.session_id = s.id AND ap.pattern_code = :patternCode
                      AND ar.id = (
                        SELECT ar2.id FROM analysis_results ar2
                        WHERE ar2.session_id = s.id
                        ORDER BY ar2.created_at DESC, ar2.id DESC LIMIT 1
                      )
              ))
            ORDER BY s.created_at DESC
            """, countQuery = """
            SELECT COUNT(DISTINCT s.id) FROM measurement_sessions s
            WHERE s.user_id = :userId
              AND (:status IS NULL OR s.status = :status)
              AND (:fromTime IS NULL OR s.created_at >= :fromTime)
              AND (:toTime IS NULL OR s.created_at <= :toTime)
              AND (:minQualityScore IS NULL OR s.data_quality_score >= :minQualityScore)
              AND (:patternCode IS NULL OR EXISTS (
                    SELECT 1 FROM analysis_results ar
                    JOIN analysis_patterns ap ON ap.analysis_result_id = ar.id
                    WHERE ar.session_id = s.id AND ap.pattern_code = :patternCode
                      AND ar.id = (
                        SELECT ar2.id FROM analysis_results ar2
                        WHERE ar2.session_id = s.id
                        ORDER BY ar2.created_at DESC, ar2.id DESC LIMIT 1
                      )
              ))
            """, nativeQuery = true)
    Page<MeasurementSession> searchHistory(
            @Param("userId") String userId,
            @Param("status") String status,
            @Param("fromTime") Instant fromTime,
            @Param("toTime") Instant toTime,
            @Param("minQualityScore") Integer minQualityScore,
            @Param("patternCode") String patternCode,
            Pageable pageable);
}
