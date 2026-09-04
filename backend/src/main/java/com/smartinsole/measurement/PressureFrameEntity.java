package com.smartinsole.measurement;

import com.smartinsole.global.common.DomainTypes.FootSide;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "pressure_frames", uniqueConstraints = @UniqueConstraint(
        name = "uk_frames_session_device_sequence", columnNames = {"session_id", "device_id", "sequence_no"}))
class PressureFrameEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "session_id", nullable = false, length = 36)
    private UUID sessionId;
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "device_id", nullable = false, length = 36)
    private UUID deviceId;
    @Enumerated(EnumType.STRING)
    @Column(name = "foot_side", nullable = false, length = 16)
    private FootSide footSide;
    @Column(name = "sequence_no", nullable = false)
    private long sequenceNo;
    @Column(name = "device_time_ms", nullable = false)
    private long deviceTimeMs;
    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;
    @Column(name = "sensor_1", nullable = false) private int sensor1;
    @Column(name = "sensor_2", nullable = false) private int sensor2;
    @Column(name = "sensor_3", nullable = false) private int sensor3;
    @Column(name = "sensor_4", nullable = false) private int sensor4;
    @Column(name = "sensor_5", nullable = false) private int sensor5;
    @Column(name = "sensor_6", nullable = false) private int sensor6;
    @Column(name = "sensor_7") private Integer sensor7;
    @Column(name = "sensor_8") private Integer sensor8;
}
