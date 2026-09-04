package com.smartinsole.measurement;

import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class PressureFrameRepository {
    private static final String INSERT_SQL = """
            INSERT INTO pressure_frames (
              session_id, device_id, foot_side, sequence_no, device_time_ms, received_at,
              sensor_1, sensor_2, sensor_3, sensor_4, sensor_5, sensor_6, sensor_7, sensor_8
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public PressureFrameRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public BatchInsertResult insertBatch(UUID sessionId, List<PressureFrameData> frames, Instant receivedAt) {
        if (frames.isEmpty()) {
            return new BatchInsertResult(0, 0, List.of());
        }
        Set<FrameKey> knownKeys = new HashSet<>(findExistingKeys(sessionId, frames));
        List<PressureFrameData> newFrames = new ArrayList<>(frames.size());
        for (PressureFrameData frame : frames) {
            if (knownKeys.add(new FrameKey(frame.deviceId(), frame.sequence()))) {
                newFrames.add(frame);
            }
        }
        int duplicateCount = frames.size() - newFrames.size();
        if (newFrames.isEmpty()) {
            return new BatchInsertResult(0, duplicateCount, List.of());
        }
        int[] counts = jdbcTemplate.batchUpdate(INSERT_SQL, new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement statement, int index) throws SQLException {
                PressureFrameData frame = newFrames.get(index);
                statement.setString(1, sessionId.toString());
                statement.setString(2, frame.deviceId().toString());
                statement.setString(3, frame.footSide().name());
                statement.setLong(4, frame.sequence());
                statement.setLong(5, frame.deviceTimeMs());
                statement.setTimestamp(6, Timestamp.from(receivedAt));
                for (int sensor = 0; sensor < 8; sensor++) {
                    if (sensor < frame.sensorValues().size()) {
                        statement.setInt(7 + sensor, frame.sensorValues().get(sensor));
                    } else {
                        statement.setNull(7 + sensor, java.sql.Types.INTEGER);
                    }
                }
            }

            @Override
            public int getBatchSize() {
                return newFrames.size();
            }
        });

        for (int count : counts) {
            if (count == Statement.EXECUTE_FAILED) {
                throw new IllegalStateException("Pressure frame batch insert failed");
            }
        }
        return new BatchInsertResult(newFrames.size(), duplicateCount, List.copyOf(newFrames));
    }

    private Set<FrameKey> findExistingKeys(UUID sessionId, List<PressureFrameData> frames) {
        Map<UUID, Set<Long>> sequencesByDevice = new LinkedHashMap<>();
        for (PressureFrameData frame : frames) {
            sequencesByDevice.computeIfAbsent(frame.deviceId(), ignored -> new LinkedHashSet<>())
                    .add(frame.sequence());
        }
        Set<FrameKey> existing = new HashSet<>();
        for (Map.Entry<UUID, Set<Long>> entry : sequencesByDevice.entrySet()) {
            String placeholders = String.join(",", Collections.nCopies(entry.getValue().size(), "?"));
            String sql = "SELECT device_id, sequence_no FROM pressure_frames "
                    + "WHERE session_id = ? AND device_id = ? AND sequence_no IN (" + placeholders + ")";
            List<Object> arguments = new ArrayList<>(entry.getValue().size() + 2);
            arguments.add(sessionId.toString());
            arguments.add(entry.getKey().toString());
            arguments.addAll(entry.getValue());
            existing.addAll(jdbcTemplate.query(sql, (resultSet, rowNumber) -> new FrameKey(
                    UUID.fromString(resultSet.getString("device_id")), resultSet.getLong("sequence_no")),
                    arguments.toArray()));
        }
        return existing;
    }

    public List<StoredPressureFrame> findBySessionOrdered(UUID sessionId) {
        return jdbcTemplate.query("""
                        SELECT device_id, foot_side, sequence_no, device_time_ms, received_at,
                               sensor_1, sensor_2, sensor_3, sensor_4, sensor_5, sensor_6, sensor_7, sensor_8
                        FROM pressure_frames
                        WHERE session_id = ?
                        ORDER BY device_time_ms ASC, sequence_no ASC, device_id ASC
                        """, (resultSet, row) -> {
                    List<Integer> values = new ArrayList<>(8);
                    for (int sensor = 1; sensor <= 8; sensor++) {
                        Integer value = (Integer) resultSet.getObject("sensor_" + sensor);
                        if (value != null) {
                            values.add(value);
                        }
                    }
                    return new StoredPressureFrame(UUID.fromString(resultSet.getString("device_id")),
                            com.smartinsole.global.common.DomainTypes.FootSide.valueOf(resultSet.getString("foot_side")),
                            resultSet.getLong("sequence_no"), resultSet.getLong("device_time_ms"),
                            resultSet.getTimestamp("received_at").toInstant(), List.copyOf(values));
                }, sessionId.toString());
    }

    public Map<UUID, Long> findMaxSequences(UUID sessionId, Set<UUID> deviceIds) {
        if (deviceIds.isEmpty()) return Map.of();
        Map<UUID, Long> maximums = new LinkedHashMap<>();
        jdbcTemplate.query("""
                        SELECT device_id, MAX(sequence_no) AS maximum_sequence
                        FROM pressure_frames
                        WHERE session_id = ?
                        GROUP BY device_id
                        """, resultSet -> {
                    UUID deviceId = UUID.fromString(resultSet.getString("device_id"));
                    if (deviceIds.contains(deviceId)) {
                        maximums.put(deviceId, resultSet.getLong("maximum_sequence"));
                    }
                }, sessionId.toString());
        return Map.copyOf(maximums);
    }

    public List<PressureFrameData> findRecentForQuality(UUID sessionId,
                                                        com.smartinsole.global.common.DomainTypes.FootSide side,
                                                        int limit) {
        return jdbcTemplate.query("""
                        SELECT device_id, foot_side, sequence_no, device_time_ms,
                               sensor_1, sensor_2, sensor_3, sensor_4,
                               sensor_5, sensor_6, sensor_7, sensor_8
                        FROM pressure_frames
                        WHERE session_id = ? AND foot_side = ?
                        ORDER BY sequence_no DESC
                        LIMIT ?
                        """, (resultSet, rowNumber) -> {
                    List<Integer> values = new ArrayList<>(8);
                    for (int sensor = 1; sensor <= 8; sensor++) {
                        Integer value = (Integer) resultSet.getObject("sensor_" + sensor);
                        if (value != null) values.add(value);
                    }
                    return new PressureFrameData(UUID.fromString(resultSet.getString("device_id")),
                            com.smartinsole.global.common.DomainTypes.FootSide.valueOf(
                                    resultSet.getString("foot_side")),
                            resultSet.getLong("sequence_no"), resultSet.getLong("device_time_ms"),
                            List.copyOf(values));
                }, sessionId.toString(), side.name(), limit);
    }

    public long countBySession(UUID sessionId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pressure_frames WHERE session_id = ?",
                Long.class, sessionId.toString());
        return count == null ? 0 : count;
    }

    public long countSequenceGaps(UUID sessionId) {
        Long gaps = jdbcTemplate.queryForObject("""
                SELECT COALESCE(SUM(gap_size), 0)
                FROM (
                    SELECT GREATEST(sequence_no
                        - LAG(sequence_no) OVER (PARTITION BY device_id ORDER BY sequence_no) - 1, 0) AS gap_size
                    FROM pressure_frames
                    WHERE session_id = ?
                ) ordered_sequences
                """, Long.class, sessionId.toString());
        return gaps == null ? 0 : gaps;
    }

    public List<SideCoverage> summarizeCoverage(UUID sessionId) {
        return jdbcTemplate.query("""
                        SELECT foot_side, COUNT(*) AS frame_count,
                               MIN(device_time_ms) AS first_device_time_ms,
                               MAX(device_time_ms) AS last_device_time_ms,
                               MAX(received_at) AS last_received_at
                        FROM pressure_frames
                        WHERE session_id = ?
                        GROUP BY foot_side
                        """, (resultSet, rowNumber) -> new SideCoverage(
                    com.smartinsole.global.common.DomainTypes.FootSide.valueOf(
                            resultSet.getString("foot_side")),
                    resultSet.getLong("frame_count"),
                    resultSet.getLong("first_device_time_ms"),
                    resultSet.getLong("last_device_time_ms"),
                    resultSet.getTimestamp("last_received_at").toInstant()),
                sessionId.toString());
    }

    public record BatchInsertResult(int acceptedCount, int duplicateCount,
                                    List<PressureFrameData> acceptedFrames) {
    }

    public record StoredPressureFrame(UUID deviceId,
                                      com.smartinsole.global.common.DomainTypes.FootSide footSide,
                                      long sequence, long deviceTimeMs, Instant receivedAt,
                                      List<Integer> sensorValues) {
    }

    public record SideCoverage(com.smartinsole.global.common.DomainTypes.FootSide footSide,
                               long frameCount, long firstDeviceTimeMs, long lastDeviceTimeMs,
                               Instant lastReceivedAt) {
    }

    private record FrameKey(UUID deviceId, long sequence) {
    }
}
