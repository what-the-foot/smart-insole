package com.smartinsole;

import static org.assertj.core.api.Assertions.assertThat;

import com.smartinsole.analysis.domain.AnalysisPattern;
import com.smartinsole.analysis.repository.AnalysisPatternRepository;
import com.smartinsole.analysis.repository.AnalysisResultRepository;
import com.smartinsole.auth.dto.AuthDtos.SignupRequest;
import com.smartinsole.auth.service.AuthService;
import com.smartinsole.device.dto.DeviceDtos.DeviceResponse;
import com.smartinsole.device.dto.DeviceDtos.RegisterDeviceRequest;
import com.smartinsole.device.service.DeviceService;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.measurement.dto.IngestionDtos.FrameBatchRequest;
import com.smartinsole.measurement.dto.IngestionDtos.PressureFrameInput;
import com.smartinsole.measurement.dto.MeasurementDtos.CreateMeasurementSessionRequest;
import com.smartinsole.measurement.repository.MeasurementQualityRepository;
import com.smartinsole.measurement.service.MeasurementService;
import com.smartinsole.measurement.repository.MeasurementSessionRepository;
import com.smartinsole.measurement.service.PressureFrameIngestionService;
import com.smartinsole.measurement.repository.PressureFrameRepository;
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
        // One contact window per foot in this scenario; observe patterns from a single window.
        registry.add("app.analysis.min-observation-windows", () -> "1");
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
        assertThat(flyway.info().applied()).hasSizeGreaterThanOrEqualTo(9);
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

        var roundTrip = ingestion.ingest(sessionId, new FrameBatchRequest("1.1", "MYSQL-TEST-RECEIVER",
                "batch-0001", Instant.now(), List.of(frame11(left, 18), frame11(right, 18))));
        assertThat(roundTrip.acceptedCount()).isEqualTo(2);
        assertThat(frames.findBySessionOrdered(sessionId)).filteredOn(stored -> stored.sequence() == 18)
                .hasSize(2)
                .allSatisfy(stored -> {
                    assertThat(stored.receiverReceivedAt()).isNotNull();
                    assertThat(stored.accelMg()).containsExactly(10, -20, 995);
                    assertThat(stored.protocolVersion()).isEqualTo(1);
                });

        measurements.complete(sessionId, userId);
        for (int attempt = 0; attempt < 100
                && sessions.findById(sessionId).orElseThrow().getStatus() != MeasurementStatus.COMPLETED; attempt++) {
            Thread.sleep(50);
        }
        assertThat(sessions.findById(sessionId).orElseThrow().getStatus()).isEqualTo(MeasurementStatus.COMPLETED);
        var storedResult = results.findBySessionIdAndAlgorithmVersion(sessionId, "rule-v1.4.0").orElseThrow();
        // rule-v1.4.0 (V9): only the two 1.1 frames carry IMU samples, so the summary is stored with the
        // coverage but without a reference or per-foot values.
        assertThat(storedResult.getMovementSummaryJson()).isNotNull()
                .contains("\"referenceMethod\":null").contains("\"left\":null").contains("\"right\":null");
        List<AnalysisPattern> storedPatterns = patterns.findAllByAnalysisResultIdOrderBySortOrder(
                storedResult.getId());
        assertThat(storedPatterns).isNotEmpty();
        String patternCode = storedPatterns.getFirst().getPatternCode();
        var history = measurements.list(userId, 0, 20, MeasurementStatus.COMPLETED,
                Instant.now().minusSeconds(3600), Instant.now().plusSeconds(3600), 0, patternCode);
        assertThat(history.totalElements()).isEqualTo(1);
        var item = history.items().getFirst();
        assertThat(item.primaryPatternCode()).isEqualTo(patternCode);
        // Contract 1.2.0 summary metrics come from the latest analysis_results row (V8 columns) on real MySQL.
        assertThat(item.algorithmVersion()).isEqualTo("rule-v1.4.0");
        assertThat(item.dataQualityLevel()).isEqualTo(storedResult.getQualityLevel());
        assertThat(item.symmetryIndex()).isEqualTo(storedResult.getSymmetryIndex());
        assertThat(item.cadence()).isEqualTo(storedResult.getCadence());
        assertThat(item.leftContactTimeMs()).isEqualTo(storedResult.getLeftContactTimeMs());
        assertThat(item.rightContactTimeMs()).isEqualTo(storedResult.getRightContactTimeMs());
        assertThat(item.validStepCount()).isEqualTo(storedResult.getValidStepCount());
        assertThat(item.leftLoadSharePct()).isEqualTo(storedResult.getLeftLoadSharePct());
        assertThat(item.rightLoadSharePct()).isEqualTo(storedResult.getRightLoadSharePct());
        assertThat(item.meanStrideTimeMs()).isEqualTo(storedResult.getMeanStrideTimeMs());
    }

    private static RegisterDeviceRequest device(String serial, FootSide side) {
        return new RegisterDeviceRequest(serial, serial, side, 8, "layout-s01s08-v1", "0.2.0");
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

    private static PressureFrameInput frame11(DeviceResponse device, long sequence) {
        return new PressureFrameInput(device.deviceId().toString(), device.footSide().name(), sequence,
                sequence * 10, List.of(500, 600, 700, 800, 900, 1000, 1100, 1200), 1,
                "2026-09-04T01:02:03.401234Z", "RAW", false, true, List.of(10, -20, 995), List.of(3, -4, 5), null);
    }

    private static FrameBatchRequest request(List<PressureFrameInput> frames) {
        return new FrameBatchRequest("1.0", "MYSQL-TEST-RECEIVER", Instant.now(), frames);
    }
}
