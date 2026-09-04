package com.smartinsole.measurement;

import com.smartinsole.global.common.DomainTypes.DataMode;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
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
              sensor_1, sensor_2, sensor_3, sensor_4, sensor_5, sensor_6, sensor_7, sensor_8,
              protocol_version, receiver_received_at, data_mode, calibrated, imu_available,
              accel_x_mg, accel_y_mg, accel_z_mg, gyro_x_dps10, gyro_y_dps10, gyro_z_dps10, flags
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;
    private static final String FRAME_COLUMNS = """
            device_id, foot_side, sequence_no, device_time_ms, received_at,
            sensor_1, sensor_2, sensor_3, sensor_4, sensor_5, sensor_6, sensor_7, sensor_8,
            protocol_version, receiver_received_at, data_mode, calibrated, imu_available,
            accel_x_mg, accel_y_mg, accel_z_mg, gyro_x_dps10, gyro_y_dps10, gyro_z_dps10, flags
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
                        statement.setNull(7 + sensor, Types.INTEGER);
                    }
                }
                setNullableInt(statement, 15, frame.protocolVersion());
                if (frame.receiverReceivedAt() == null) {
                    statement.setNull(16, Types.TIMESTAMP);
                } else {
                    statement.setTimestamp(16, Timestamp.from(frame.receiverReceivedAt()));
                }
                if (frame.dataMode() == null) {
                    statement.setNull(17, Types.VARCHAR);
                } else {
                    statement.setString(17, frame.dataMode().name());
                }
                setNullableBoolean(statement, 18, frame.calibrated());
                setNullableBoolean(statement, 19, frame.imuAvailable());
                for (int axis = 0; axis < 3; axis++) {
                    setNullableInt(statement, 20 + axis, frame.accelMg() == null ? null : frame.accelMg().get(axis));
                    setNullableInt(statement, 23 + axis,
                            frame.gyroDps10() == null ? null : frame.gyroDps10().get(axis));
                }
                setNullableInt(statement, 26, frame.flags());
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

    private static void setNullableInt(PreparedStatement statement, int index, Integer value) throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.INTEGER);
        } else {
            statement.setInt(index, value);
        }
    }

    private static void setNullableBoolean(PreparedStatement statement, int index, Boolean value)
            throws SQLException {
        if (value == null) {
            statement.setNull(index, Types.BOOLEAN);
        } else {
            statement.setBoolean(index, value);
        }
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
        return jdbcTemplate.query("SELECT " + FRAME_COLUMNS + """
                        FROM pressure_frames
                        WHERE session_id = ?
                        ORDER BY device_time_ms ASC, sequence_no ASC, device_id ASC
                        """, (resultSet, row) -> {
                    PressureFrameData data = readFrame(resultSet);
                    return new StoredPressureFrame(data.deviceId(), data.footSide(), data.sequence(),
                            data.deviceTimeMs(), resultSet.getTimestamp("received_at").toInstant(),
                            data.sensorValues(), data.protocolVersion(), data.receiverReceivedAt(),
                            data.dataMode(), data.calibrated(), data.imuAvailable(), data.accelMg(),
                            data.gyroDps10(), data.flags());
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

    public List<PressureFrameData> findRecentForQuality(UUID sessionId, FootSide side, int limit) {
        return jdbcTemplate.query("SELECT " + FRAME_COLUMNS + """
                        FROM pressure_frames
                        WHERE session_id = ? AND foot_side = ?
                        ORDER BY sequence_no DESC
                        LIMIT ?
                        """, (resultSet, rowNumber) -> readFrame(resultSet),
                sessionId.toString(), side.name(), limit);
    }

    private static PressureFrameData readFrame(ResultSet resultSet) throws SQLException {
        List<Integer> values = new ArrayList<>(8);
        for (int sensor = 1; sensor <= 8; sensor++) {
            Integer value = resultSet.getObject("sensor_" + sensor, Integer.class);
            if (value != null) values.add(value);
        }
        Timestamp receiverReceivedAt = resultSet.getTimestamp("receiver_received_at");
        String dataMode = resultSet.getString("data_mode");
        return new PressureFrameData(UUID.fromString(resultSet.getString("device_id")),
                FootSide.valueOf(resultSet.getString("foot_side")),
                resultSet.getLong("sequence_no"), resultSet.getLong("device_time_ms"), List.copyOf(values),
                resultSet.getObject("protocol_version", Integer.class),
                receiverReceivedAt == null ? null : receiverReceivedAt.toInstant(),
                dataMode == null ? null : DataMode.valueOf(dataMode),
                resultSet.getObject("calibrated", Boolean.class),
                resultSet.getObject("imu_available", Boolean.class),
                vector(resultSet, "accel_x_mg", "accel_y_mg", "accel_z_mg"),
                vector(resultSet, "gyro_x_dps10", "gyro_y_dps10", "gyro_z_dps10"),
                resultSet.getObject("flags", Integer.class));
    }

    private static List<Integer> vector(ResultSet resultSet, String x, String y, String z) throws SQLException {
        Integer first = resultSet.getObject(x, Integer.class);
        Integer second = resultSet.getObject(y, Integer.class);
        Integer third = resultSet.getObject(z, Integer.class);
        if (first == null || second == null || third == null) return null;
        return List.of(first, second, third);
    }

    public long countBySession(UUID sessionId) {
        Long count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM pressure_frames WHERE session_id = ?",
                Long.class, sessionId.toString());
        return count == null ? 0 : count;
    }

    /**
     * Authoritative gap count used once at session finalisation to reconcile the O(1) per-batch
     * arithmetic: sum over devices of (last - first + 1 - rows), i.e. the number of missing sequences.
     */
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
                    FootSide.valueOf(resultSet.getString("foot_side")),
                    resultSet.getLong("frame_count"),
                    resultSet.getLong("first_device_time_ms"),
                    resultSet.getLong("last_device_time_ms"),
                    resultSet.getTimestamp("last_received_at").toInstant()),
                sessionId.toString());
    }

    public record BatchInsertResult(int acceptedCount, int duplicateCount,
                                    List<PressureFrameData> acceptedFrames) {
    }

    /**
     * Frame as read back for analysis. {@code receivedAt} is the batch server receipt time and
     * {@code receiverReceivedAt} the per-frame receiver (PC) time from schemaVersion 1.1; the latter and
     * the remaining metadata are null for 1.0 rows.
     */
    public record StoredPressureFrame(UUID deviceId, FootSide footSide, long sequence, long deviceTimeMs,
                                      Instant receivedAt, List<Integer> sensorValues,
                                      Integer protocolVersion, Instant receiverReceivedAt, DataMode dataMode,
                                      Boolean calibrated, Boolean imuAvailable, List<Integer> accelMg,
                                      List<Integer> gyroDps10, Integer flags) {
        public StoredPressureFrame {
            sensorValues = List.copyOf(sensorValues);
            accelMg = accelMg == null ? null : List.copyOf(accelMg);
            gyroDps10 = gyroDps10 == null ? null : List.copyOf(gyroDps10);
        }

        public StoredPressureFrame(UUID deviceId, FootSide footSide, long sequence, long deviceTimeMs,
                                   Instant receivedAt, List<Integer> sensorValues) {
            this(deviceId, footSide, sequence, deviceTimeMs, receivedAt, sensorValues, null, null, null, null,
                    null, null, null, null);
        }
    }

    public record SideCoverage(FootSide footSide, long frameCount, long firstDeviceTimeMs,
                               long lastDeviceTimeMs, Instant lastReceivedAt) {
    }

    private record FrameKey(UUID deviceId, long sequence) {
    }
}
