package com.smartinsole.measurement;

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

    public Map<UUID, String> primaryPatterns(List<UUID> sessionIds) {
        if (sessionIds.isEmpty()) return Map.of();
        String placeholders = String.join(",", Collections.nCopies(sessionIds.size(), "?"));
        String sql = """
                SELECT ar.session_id, ap.pattern_code
                FROM analysis_results ar
                JOIN analysis_patterns ap ON ap.analysis_result_id = ar.id
                WHERE ar.session_id IN (%s)
                  AND ar.id = (
                    SELECT ar2.id FROM analysis_results ar2
                    WHERE ar2.session_id = ar.session_id
                    ORDER BY ar2.created_at DESC, ar2.id DESC LIMIT 1
                  )
                  AND ap.sort_order = (
                    SELECT MIN(ap2.sort_order) FROM analysis_patterns ap2
                    WHERE ap2.analysis_result_id = ar.id
                  )
                """.formatted(placeholders);
        Map<UUID, String> values = new LinkedHashMap<>();
        jdbcTemplate.query(sql, (resultSet, rowNumber) -> Map.entry(
                        UUID.fromString(resultSet.getString("session_id")),
                        resultSet.getString("pattern_code")),
                sessionIds.stream().map(UUID::toString).toArray())
                .forEach(entry -> values.put(entry.getKey(), entry.getValue()));
        return Map.copyOf(values);
    }
}
