package com.smartinsole.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartinsole.device.Device;
import com.smartinsole.device.DeviceRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.ReceiverUploadState;
import com.smartinsole.global.common.DomainTypes.SourceType;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.IngestionDtos.FrameBatchRequest;
import com.smartinsole.measurement.IngestionDtos.FrameBatchResponse;
import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.IngestionDtos.PressureFrameInput;
import com.smartinsole.measurement.IngestionDtos.ReceiverStatusRequest;
import com.smartinsole.measurement.PressureFrameRepository.BatchInsertResult;
import com.smartinsole.support.TestIngestionProperties;
import com.smartinsole.support.TestSessions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class PressureFrameIngestionServiceTest {
    private static final String LAYOUT = "layout-s01s08-v1";
    private static final List<Integer> VALUES = List.of(120, 110, 60, 55, 70, 65, 60, 40);
    private final Instant now = Instant.parse("2026-09-04T01:02:03Z");
    private final UUID userId = UUID.randomUUID();
    private final MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final PressureFrameRepository frames = mock(PressureFrameRepository.class);
    private final QualityService qualityService = mock(QualityService.class);
    private final ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    private final Device left = Device.register(userId, "SMART-INSOLE-L-00000001", "L", FootSide.LEFT, 8, LAYOUT,
            "0.2.0", 4095, Instant.parse("2026-09-04T00:00:00Z"));
    private final Device right = Device.register(userId, "SMART-INSOLE-R-00000002", "R", FootSide.RIGHT, 8, LAYOUT,
            "0.2.0", 4095, Instant.parse("2026-09-04T00:00:00Z"));
    private final AtomicReference<List<PressureFrameData>> inserted = new AtomicReference<>(List.of());
    private final PressureFrameIngestionService service = new PressureFrameIngestionService(sessions, devices, frames,
            qualityService, events, TestIngestionProperties.defaults(), Clock.fixed(now, ZoneOffset.UTC));
    private MeasurementSession session;

    @BeforeEach
    void setUp() {
        session = TestSessions.create(userId, left.getId(), right.getId(), UUID.randomUUID(), UUID.randomUUID(),
                LAYOUT, LAYOUT, 50, SourceType.DEVICE, 4095, null, now.minusSeconds(10));
        when(sessions.findByIdForUpdate(session.getId())).thenReturn(Optional.of(session));
        when(devices.findById(left.getId())).thenReturn(Optional.of(left));
        when(devices.findById(right.getId())).thenReturn(Optional.of(right));
        when(frames.insertBatch(any(), anyList(), any())).thenAnswer(invocation -> {
            List<PressureFrameData> valid = invocation.getArgument(1);
            inserted.set(List.copyOf(valid));
            return new BatchInsertResult(valid.size(), 0, List.copyOf(valid));
        });
        when(frames.findMaxSequences(any(), any())).thenReturn(Map.of());
    }

    @Test
    void createdSessionAnswersRetryDispositionWithRetryAfter() {
        assertThatThrownBy(() -> service.ingest(session.getId(), batch("1.0", frame(left, 1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.SESSION_NOT_MEASURING);
                    assertThat(error.details()).containsEntry("currentStatus", "CREATED")
                            .containsEntry("disposition", "RETRY");
                    assertThat(error.headers()).containsEntry("X-Batch-Disposition", "RETRY")
                            .containsEntry("Retry-After", "2");
                });
    }

    @Test
    void endedSessionAnswersDropDispositionWithoutRetryAfter() {
        session.start(now.minusSeconds(9));
        session.complete(now.minusSeconds(1));

        assertThatThrownBy(() -> service.ingest(session.getId(), batch("1.0", frame(left, 1))))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.details()).containsEntry("currentStatus", "PROCESSING")
                            .containsEntry("disposition", "DROP");
                    assertThat(error.headers()).containsEntry("X-Batch-Disposition", "DROP")
                            .doesNotContainKey("Retry-After");
                });
    }

    @Test
    void legacyBatchRejectsFramesCarryingSchema11Fields() {
        session.start(now.minusSeconds(9));
        PressureFrameInput withDataMode = new PressureFrameInput(left.getId().toString(), "LEFT", 1L, 20L, VALUES,
                null, null, "RAW", null, null, null, null, null);

        FrameBatchResponse response = service.ingest(session.getId(), batch("1.0", withDataMode, frame(right, 1)));

        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.rejectedCount()).isEqualTo(1);
        assertThat(response.rejections()).singleElement().satisfies(rejection -> {
            assertThat(rejection.frameIndex()).isZero();
            assertThat(rejection.code()).isEqualTo("SCHEMA_FIELD_NOT_ALLOWED");
        });
    }

    @Test
    void schema11FieldsAreValidatedPerFrame() {
        session.start(now.minusSeconds(9));
        List<PressureFrameInput> inputs = List.of(
                frame11(left, 1, 0, "2026-09-04T01:02:03.401234Z", "RAW", null, null, null),
                frame11(left, 2, 1, "yesterday", "RAW", null, null, null),
                frame11(left, 3, 1, "2026-09-04T01:02:03.401234Z", "SMOOTHED", null, null, null),
                frame11(left, 4, 1, "2026-09-04T01:02:03.401234Z", "RAW", 256, null, null),
                frame11(left, 5, 1, "2026-09-04T01:02:03.401234Z", "RAW", null, List.of(1, 2), List.of(1, 2, 3)),
                frame11(left, 6, 1, "2026-09-04T01:02:03.401234Z", "RAW", null, List.of(1, 2, 3), null),
                frame11(left, 7, 1, "2026-09-04T01:02:03.401234Z", "RAW", null, List.of(40000, 0, 0), List.of(0, 0, 0)),
                frame11(left, 8, 2, "2026-09-04T01:02:03.401234+00:00", "FILTERED", 5, List.of(10, -20, 995),
                        List.of(3, -4, 5)));

        FrameBatchResponse response = service.ingest(session.getId(), batch("1.1", inputs.toArray(PressureFrameInput[]::new)));

        assertThat(response.rejections()).extracting(IngestionDtos.FrameRejection::code).containsExactly(
                "INVALID_PROTOCOL_VERSION", "INVALID_RECEIVED_AT", "INVALID_DATA_MODE", "INVALID_FLAGS",
                "INVALID_IMU", "INVALID_IMU", "INVALID_IMU");
        assertThat(response.acceptedCount()).isEqualTo(1);
        PressureFrameData stored = inserted.get().getFirst();
        assertThat(stored.sequence()).isEqualTo(8);
        assertThat(stored.protocolVersion()).isEqualTo(2);
        assertThat(stored.receiverReceivedAt()).isEqualTo(Instant.parse("2026-09-04T01:02:03.401234Z"));
        assertThat(stored.dataMode()).isEqualTo(com.smartinsole.global.common.DomainTypes.DataMode.FILTERED);
        assertThat(stored.flags()).isEqualTo(5);
        assertThat(stored.accelMg()).containsExactly(10, -20, 995);
    }

    @Test
    void imuErrorFlagNormalisesImuAvailableToFalse() {
        session.start(now.minusSeconds(9));
        PressureFrameInput imuError = new PressureFrameInput(left.getId().toString(), "LEFT", 1L, 20L, VALUES,
                2, null, "RAW", false, true, List.of(0, 0, 0), List.of(0, 0, 0), 0b010);
        PressureFrameInput batteryLow = new PressureFrameInput(left.getId().toString(), "LEFT", 2L, 40L, VALUES,
                2, null, "RAW", false, true, List.of(0, 0, 0), List.of(0, 0, 0), 0b100);

        service.ingest(session.getId(), batch("1.1", imuError, batteryLow));

        assertThat(inserted.get()).extracting(PressureFrameData::imuAvailable).containsExactly(false, true);
    }

    @Test
    void boundsSequenceToU32AndSensorValuesToTheSessionAdcMax() {
        session.start(now.minusSeconds(9));
        List<Integer> saturated = new ArrayList<>(VALUES);
        saturated.set(0, 4096);

        FrameBatchResponse response = service.ingest(session.getId(), batch("1.1",
                frame(left, PressureFrameIngestionService.MAX_SEQUENCE),
                frame(left, PressureFrameIngestionService.MAX_SEQUENCE + 1),
                new PressureFrameInput(right.getId().toString(), "RIGHT", 1L, 20L, saturated)));

        assertThat(response.acceptedCount()).isEqualTo(1);
        assertThat(response.rejections()).extracting(IngestionDtos.FrameRejection::code)
                .containsExactly("INVALID_SEQUENCE", "INVALID_ADC_VALUE");
        assertThat(response.rejections().getLast().message()).contains("4095");
    }

    @Test
    void rejectsUnsupportedSchemaVersionsAndOversizedBatchesAsAWhole() {
        session.start(now.minusSeconds(9));
        List<PressureFrameInput> tooMany = new ArrayList<>();
        for (int sequence = 0; sequence <= 200; sequence++) tooMany.add(frame(left, sequence));

        assertThatThrownBy(() -> service.ingest(session.getId(), batch("1.2", frame(left, 1))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.UNSUPPORTED_SCHEMA_VERSION));
        assertThatThrownBy(() -> service.ingest(session.getId(),
                new FrameBatchRequest("1.1", "GATEWAY-DEV-001", "batch-1", now, tooMany)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.PAYLOAD_TOO_LARGE));
    }

    @Test
    void recordsReceiverIdentityOnFirstBatchAndStatusOnlyWhileMeasuring() {
        session.start(now.minusSeconds(9));

        service.ingest(session.getId(), batch("1.1", frame(left, 1)));
        service.recordReceiverStatus(session.getId(), new ReceiverStatusRequest("GATEWAY-DEV-001",
                ReceiverUploadState.UPLOADING, 3, now));

        assertThat(session.getReceiverId()).isEqualTo("GATEWAY-DEV-001");
        assertThat(session.getReceiverState()).isEqualTo(ReceiverUploadState.UPLOADING);
        assertThat(session.getReceiverPendingBatches()).isEqualTo(3);

        session.complete(now);
        assertThatThrownBy(() -> service.recordReceiverStatus(session.getId(), new ReceiverStatusRequest(
                "GATEWAY-DEV-001", ReceiverUploadState.UPLOAD_COMPLETE, 0, now.plusSeconds(1))))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.SESSION_NOT_MEASURING));
    }

    private static FrameBatchRequest batch(String schemaVersion, PressureFrameInput... inputs) {
        return new FrameBatchRequest(schemaVersion, "GATEWAY-DEV-001",
                "1.1".equals(schemaVersion) ? "batch-1" : null, Instant.parse("2026-09-04T01:02:03.456789Z"),
                List.of(inputs));
    }

    private static PressureFrameInput frame(Device device, long sequence) {
        return PressureFrameInput.v10(device.getId().toString(), device.getFootSide().name(), sequence,
                sequence * 20, VALUES);
    }

    private static PressureFrameInput frame11(Device device, long sequence, Integer protocolVersion,
                                              String receivedAt, String dataMode, Integer flags,
                                              List<Integer> accelMg, List<Integer> gyroDps10) {
        return new PressureFrameInput(device.getId().toString(), device.getFootSide().name(), sequence,
                sequence * 20, VALUES, protocolVersion, receivedAt, dataMode, false, accelMg != null, accelMg,
                gyroDps10, flags);
    }
}
