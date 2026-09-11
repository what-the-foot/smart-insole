package com.smartinsole.measurement.repository;

import com.smartinsole.global.common.DomainTypes.QualityLevel;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class HistoryProjectionRepository {
    private final JdbcTemplate jdbcTemplate;

    public HistoryProjectionRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * Summary of a session's latest {@code analysis_results} row for the history list (contract 1.2.0).
     * {@code primaryPatternCode} is the pattern with the lowest sort order, null when the result has none.
     * {@code validStepCount} is null for results older than rule-v1.1.0; the load share and mean stride time
     * are null for results older than rule-v1.3.0 or when the analyzer could not derive them.
     */
    public record LatestResultSummary(
            String primaryPatternCode,
            String algorithmVersion,
            QualityLevel dataQualityLevel,
            double symmetryIndex,
            double cadence,
            double leftContactTimeMs,
            double rightContactTimeMs,
            Integer validStepCount,
            Double leftLoadSharePct,
            Double rightLoadSharePct,
            Double meanStrideTimeMs
    ) {
    }

    /**
     * One query for the whole page: the latest result per session (created_at, then id) joined with its
     * first pattern. Sessions without any result are absent from the map.
     */
    public Map<UUID, LatestResultSummary> latestResults(List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
        String sql = """
                SELECT ar.session_id, ar.algorithm_version, ar.quality_level, ar.symmetry_index, ar.cadence,
                       ar.left_contact_time_ms, ar.right_contact_time_ms, ar.valid_step_count,
                       ar.left_load_share_pct, ar.right_load_share_pct, ar.mean_stride_time_ms,
                       ap.pattern_code
                FROM analysis_results ar
                LEFT JOIN analysis_patterns ap ON ap.analysis_result_id = ar.id
                  AND ap.sort_order = (
                    SELECT MIN(ap2.sort_order) FROM analysis_patterns ap2
                    WHERE ap2.analysis_result_id = ar.id
                  )
                WHERE ar.session_id IN (%s)
                  AND ar.id = (
                    SELECT ar2.id FROM analysis_results ar2
                    WHERE ar2.session_id = ar.session_id
                    ORDER BY ar2.created_at DESC, ar2.id DESC LIMIT 1
                  )
                """.formatted(placeholders);
        Map<UUID, LatestResultSummary> values = new LinkedHashMap<>();
        jdbcTemplate.query(sql, (resultSet, rowNumber) -> Map.entry(
                        UUID.fromString(resultSet.getString("session_id")), summary(resultSet)),
                sessionIds.stream().map(UUID::toString).toArray())
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(values);
    }

    private static LatestResultSummary summary(ResultSet resultSet) throws SQLException {
        String qualityLevel = resultSet.getString("quality_level");
        return new LatestResultSummary(
                resultSet.getString("pattern_code"),
                resultSet.getString("algorithm_version"),
                qualityLevel == null ? null : QualityLevel.valueOf(qualityLevel),
                resultSet.getDouble("symmetry_index"),
                resultSet.getDouble("cadence"),
                resultSet.getDouble("left_contact_time_ms"),
                resultSet.getDouble("right_contact_time_ms"),
                nullableInt(resultSet, "valid_step_count"),
                nullableDouble(resultSet, "left_load_share_pct"),
                nullableDouble(resultSet, "right_load_share_pct"),
                nullableDouble(resultSet, "mean_stride_time_ms"));
    }

    private static Integer nullableInt(ResultSet resultSet, String column) throws SQLException {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Double nullableDouble(ResultSet resultSet, String column) throws SQLException {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }
}
