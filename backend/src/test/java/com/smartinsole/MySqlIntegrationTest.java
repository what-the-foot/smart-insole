package com.smartinsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartinsole.analysis.AnalysisPattern;
import com.smartinsole.analysis.AnalysisPatternRepository;
import com.smartinsole.analysis.AnalysisResultRepository;
import com.smartinsole.auth.AuthDtos.SignupRequest;
import com.smartinsole.auth.AuthService;
import com.smartinsole.device.DeviceDtos.DeviceResponse;
import com.smartinsole.device.DeviceDtos.RegisterDeviceRequest;
import com.smartinsole.device.DeviceService;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.measurement.IngestionDtos.FrameBatchRequest;
import com.smartinsole.measurement.IngestionDtos.PressureFrameInput;
import com.smartinsole.measurement.MeasurementDtos.CreateMeasurementSessionRequest;
import com.smartinsole.measurement.MeasurementQualityRepository;
import com.smartinsole.measurement.MeasurementService;
import com.smartinsole.measurement.MeasurementSessionRepository;
import com.smartinsole.measurement.PressureFrameIngestionService;
import com.smartinsole.measurement.PressureFrameRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(properties = "spring.profiles.active=mysqltest")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@Testcontainers(disabledWithoutDocker = true)
class MySqlIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("smart_insole")
            .withUsername("smart_insole")
            .withPassword("test-only-password");

    @DynamicPropertySource
    static void mysqlProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url",
                () -> MYSQL.getJdbcUrl() + (MYSQL.getJdbcUrl().contains("?") ? "&" : "?")
                        + "rewriteBatchedStatements=true&serverTimezone=UTC");
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("app.auth.jwt-secret",
                () -> "mysql-test-only-secret-key-with-at-least-thirty-two-bytes");
        registry.add("app.receiver.api-key", () -> "mysql-test-receiver-key");
    }

    @Autowired Flyway flyway;
    @Autowired AuthService auth;
    @Autowired DeviceService devices;
    @Autowired MeasurementService measurements;
    @Autowired PressureFrameIngestionService ingestion;
    @Autowired PressureFrameRepository frames;
    @Autowired MeasurementQualityRepository qualities;
    @Autowired MeasurementSessionRepository sessions;
    @Autowired AnalysisPatternRepository patterns;
    @Autowired AnalysisResultRepository results;

    @Test
    void validatesSchemaBatchIdempotencyWindowQueriesAndHistoryOnRealMySql() throws Exception {
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(4);
        UUID userId = auth.signup(new SignupRequest("mysql@example.com", "password123", "MySQL Test")).userId();
        DeviceResponse left = devices.register(userId, device("MYSQL-L-001", FootSide.LEFT));
        DeviceResponse right = devices.register(userId, device("MYSQL-R-001", FootSide.RIGHT));
        UUID sessionId = measurements.create(userId, new CreateMeasurementSessionRequest(
                left.deviceId(), right.deviceId(), 100, "mysql integration")).sessionId();
        measurements.start(sessionId, userId);

        var allNew = ingestion.ingest(sessionId, request(frames(left, right, 1, 10)));
        assertThat(allNew.acceptedCount()).isEqualTo(20);
        assertThat(allNew.duplicateCount()).isZero();

        List<PressureFrameInput> mixedFrames = new ArrayList<>();
        for (long sequence = 1; sequence <= 5; sequence++) mixedFrames.add(frame(left, sequence));
        for (long sequence = 11; sequence <= 15; sequence++) mixedFrames.add(frame(left, sequence));
        PressureFrameInput repeated = frame(right, 11);
        mixedFrames.add(repeated);
        mixedFrames.add(repeated);
        for (long sequence = 12; sequence <= 15; sequence++) mixedFrames.add(frame(right, sequence));
        var mixed = ingestion.ingest(sessionId, request(mixedFrames));
        assertThat(mixed.acceptedCount()).isEqualTo(10);
        assertThat(mixed.duplicateCount()).isEqualTo(6);
        assertThat(frames.countBySession(sessionId)).isEqualTo(30);

        ingestion.ingest(sessionId, request(List.of(frame(left, 17), frame(right, 17))));
        assertThat(qualities.findById(sessionId).orElseThrow().getSequenceGapCount()).isEqualTo(2);
        ingestion.ingest(sessionId, request(List.of(frame(left, 16), frame(right, 16))));
        assertThat(qualities.findById(sessionId).orElseThrow().getSequenceGapCount()).isZero();

        measurements.complete(sessionId, userId);
        for (int attempt = 0; attempt < 100
                && sessions.findById(sessionId).orElseThrow().getStatus() != MeasurementStatus.COMPLETED; attempt++) {
            Thread.sleep(50);
        }
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(MeasurementStatus.COMPLETED);
        List<AnalysisPattern> storedPatterns = patterns.findAllByAnalysisResultIdOrderBySortOrder(
                results.findBySessionIdAndAlgorithmVersion(sessionId, "rule-v1.1.0").orElseThrow().getId());
        assertThat(storedPatterns).isNotEmpty();
        String patternCode = storedPatterns.getFirst().getPatternCode();
        var history = measurements.list(userId, 0, 20, MeasurementStatus.COMPLETED,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), 0, patternCode);
        assertThat(history.totalElements()).isEqualTo(1);
        assertThat(history.items().getFirst().primaryPatternCode()).isEqualTo(patternCode);
    }

    private static RegisterDeviceRequest device(String serial, FootSide side) {
        return new RegisterDeviceRequest(serial, serial, side, 8, "layout-v1", "0.1.0");
    }

    private static List<PressureFrameInput> frames(DeviceResponse left, DeviceResponse right,
                                                    long first, long last) {
        List<PressureFrameInput> values = new ArrayList<>();
        for (long sequence = first; sequence <= last; sequence++) {
            values.add(frame(left, sequence));
            values.add(frame(right, sequence));
        }
        return values;
    }

    private static PressureFrameInput frame(DeviceResponse device, long sequence) {
        return new PressureFrameInput(device.deviceId().toString(), device.footSide().name(), sequence,
                sequence * 10, List.of(500, 600, 700, 800, 900, 1000, 1100, 1200));
    }

    private static FrameBatchRequest request(List<PressureFrameInput> frames) {
        return new FrameBatchRequest("1.0", "MYSQL-TEST-RECEIVER", Instant.now(), frames);
    }
}
