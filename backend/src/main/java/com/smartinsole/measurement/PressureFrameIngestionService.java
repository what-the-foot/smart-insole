package com.smartinsole.measurement;

import com.smartinsole.device.Device;
import com.smartinsole.device.DeviceRepository;
import com.smartinsole.global.common.DomainTypes.DataMode;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.config.IngestionProperties;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.IngestionDtos.FrameBatchRequest;
import com.smartinsole.measurement.IngestionDtos.FrameBatchResponse;
import com.smartinsole.measurement.IngestionDtos.FrameRejection;
import com.smartinsole.measurement.IngestionDtos.FramesPersistedEvent;
import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.IngestionDtos.PressureFrameInput;
import com.smartinsole.measurement.IngestionDtos.ReceiverStatusRequest;
import java.time.Clock;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PressureFrameIngestionService {
    public static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("1.0", "1.1");
    public static final String DISPOSITION_RETRY = "RETRY";
    public static final String DISPOSITION_DROP = "DROP";
    public static final String DISPOSITION_HEADER = "X-Batch-Disposition";
    /** Sensor Data flags bit1 (IMU_ERROR): the receiver's imuAvailable is normalised to false. */
    static final int FLAG_IMU_ERROR = 0b10;
    private static final Logger log = LoggerFactory.getLogger(PressureFrameIngestionService.class);
    private static final int IMU_AXES = 3;
    private static final int INT16_MIN = -32768;
    private static final int INT16_MAX = 32767;
    private static final int MAX_FLAGS = 255;

    private final MeasurementSessionRepository sessions;
    private final DeviceRepository devices;
    private final PressureFrameRepository frames;
    private final QualityService qualityService;
    private final ApplicationEventPublisher events;
    private final IngestionProperties properties;
    private final Clock clock;

    public PressureFrameIngestionService(MeasurementSessionRepository sessions, DeviceRepository devices,
                                         PressureFrameRepository frames, QualityService qualityService,
                                         ApplicationEventPublisher events, IngestionProperties properties,
                                         Clock clock) {
        this.sessions = sessions;
        this.devices = devices;
        this.frames = frames;
        this.qualityService = qualityService;
        this.events = events;
        this.properties = properties;
        this.clock = clock;
    }

    @Transactional
    public FrameBatchResponse ingest(UUID sessionId, FrameBatchRequest request) {
        validateEnvelope(request);
        // The database lock serializes batches for one session across all application instances and
        // avoids retaining one in-memory lock object for every session ever seen by this process.
        MeasurementSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (session.getStatus() != MeasurementStatus.MEASURING) {
            throw notMeasuring(session, request);
        }
        Map<UUID, Device> assigned = new HashMap<>();
        assigned.put(session.getLeftDeviceId(), devices.findById(session.getLeftDeviceId()).orElseThrow());
        assigned.put(session.getRightDeviceId(), devices.findById(session.getRightDeviceId()).orElseThrow());

        boolean legacySchema = "1.0".equals(request.schemaVersion());
        List<PressureFrameData> valid = new ArrayList<>();
        List<FrameRejection> rejections = new ArrayList<>();
        for (int index = 0; index < request.frames().size(); index++) {
            Validation validation = validateFrame(request.frames().get(index), assigned, legacySchema);
            if (validation.rejectionCode() == null) {
                valid.add(validation.frame());
            } else {
                rejections.add(new FrameRejection(index, validation.rejectionCode(), validation.message()));
            }
        }

        Instant receivedAt = Instant.now(clock);
        // The first batch pins the receiver identity on the session inside the same locked transaction.
        session.recordReceiver(request.receiverId().trim(), receivedAt);
        PressureFrameRepository.BatchInsertResult inserted = frames.insertBatch(sessionId, valid, receivedAt);
        qualityService.update(session, inserted.acceptedFrames(), inserted.acceptedCount(),
                inserted.duplicateCount(), rejections.size(), receivedAt);
        if (!inserted.acceptedFrames().isEmpty()) {
            events.publishEvent(new FramesPersistedEvent(sessionId, inserted.acceptedFrames(), receivedAt));
        }
        Set<UUID> validDeviceIds = new LinkedHashSet<>();
        valid.forEach(frame -> validDeviceIds.add(frame.deviceId()));
        Map<UUID, Long> lastSequences = frames.findMaxSequences(sessionId, validDeviceIds);
        return new FrameBatchResponse(inserted.acceptedCount(), inserted.duplicateCount(), rejections.size(),
                List.copyOf(rejections), Map.copyOf(lastSequences), receivedAt);
    }

    /**
     * Applies a receiver upload status report. The row lock prevents an @Version conflict with a
     * concurrent frame batch; only MEASURING sessions accept reports (409 SESSION_NOT_MEASURING otherwise).
     */
    @Transactional
    public void recordReceiverStatus(UUID sessionId, ReceiverStatusRequest request) {
        MeasurementSession session = sessions.findByIdForUpdate(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (session.getStatus() != MeasurementStatus.MEASURING) {
            throw new BusinessException(ErrorCode.SESSION_NOT_MEASURING,
                    ErrorCode.SESSION_NOT_MEASURING.defaultMessage(),
                    Map.of("currentStatus", session.getStatus().name()));
        }
        session.recordReceiverStatus(request.receiverId().trim(), request.state(), request.pendingBatchCount(),
                request.observedAt());
    }

    private BusinessException notMeasuring(MeasurementSession session, FrameBatchRequest request) {
        boolean retry = session.getStatus() == MeasurementStatus.CREATED;
        String disposition = retry ? DISPOSITION_RETRY : DISPOSITION_DROP;
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put(DISPOSITION_HEADER, disposition);
        if (retry) {
            headers.put("Retry-After", String.valueOf(properties.retryAfterSeconds()));
        } else {
            log.warn("Dropping frame batch for session {} in status {}: {} frame(s) from receiver {}",
                    session.getId(), session.getStatus(), request.frames().size(), request.receiverId());
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("currentStatus", session.getStatus().name());
        details.put("disposition", disposition);
        return new BusinessException(ErrorCode.SESSION_NOT_MEASURING,
                ErrorCode.SESSION_NOT_MEASURING.defaultMessage(), details, headers);
    }

    private void validateEnvelope(FrameBatchRequest request) {
        if (request == null || request.receiverId() == null || request.receiverId().isBlank()
                || request.sentAt() == null || request.frames() == null || request.frames().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (request.schemaVersion() == null || !SUPPORTED_SCHEMA_VERSIONS.contains(request.schemaVersion())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_SCHEMA_VERSION);
        }
        if (request.receiverId().length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (request.batchId() != null && (request.batchId().isBlank() || request.batchId().length() > 100)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (request.frames().size() > properties.maxFramesPerBatch()) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
    }

    private static Validation validateFrame(PressureFrameInput input, Map<UUID, Device> assigned,
                                            boolean legacySchema) {
        if (input == null) return Validation.reject("INVALID_FRAME", "프레임이 null입니다.");
        UUID deviceId;
        try {
            deviceId = UUID.fromString(input.deviceId());
        } catch (RuntimeException exception) {
            return Validation.reject("INVALID_DEVICE_ID", "deviceId가 UUID 형식이 아닙니다.");
        }
        Device device = assigned.get(deviceId);
        if (device == null) return Validation.reject("DEVICE_NOT_ASSIGNED", "세션에 배정되지 않은 기기입니다.");
        FootSide side;
        try {
            side = FootSide.valueOf(input.footSide());
        } catch (RuntimeException exception) {
            return Validation.reject("INVALID_FOOT_SIDE", "footSide가 올바르지 않습니다.");
        }
        if (side != device.getFootSide()) {
            return Validation.reject("FOOT_SIDE_MISMATCH", "기기 방향과 footSide가 다릅니다.");
        }
        if (input.sequence() == null || input.sequence() < 0) {
            return Validation.reject("INVALID_SEQUENCE", "sequence는 0 이상이어야 합니다.");
        }
        if (input.deviceTimeMs() == null || input.deviceTimeMs() < 0) {
            return Validation.reject("INVALID_DEVICE_TIME", "deviceTimeMs는 0 이상이어야 합니다.");
        }
        if (input.sensorValues() == null || input.sensorValues().size() != device.getSensorCount()) {
            return Validation.reject("INVALID_SENSOR_COUNT", "기기 센서 수와 전달된 배열 길이가 다릅니다.");
        }
        for (Integer value : input.sensorValues()) {
            if (value == null || value < 0 || value > 65535) {
                return Validation.reject("INVALID_ADC_VALUE", "센서 값은 0에서 65535 사이여야 합니다.");
            }
        }
        if (legacySchema) {
            if (input.hasSchema11Fields()) {
                return Validation.reject("SCHEMA_FIELD_NOT_ALLOWED",
                        "schemaVersion 1.0 배치에는 1.1 프레임 필드를 넣을 수 없습니다.");
            }
            return Validation.accept(new PressureFrameData(deviceId, side, input.sequence(), input.deviceTimeMs(),
                    input.sensorValues()));
        }
        if (input.protocolVersion() != null && input.protocolVersion() < 1) {
            return Validation.reject("INVALID_PROTOCOL_VERSION", "protocolVersion은 1 이상이어야 합니다.");
        }
        Instant receivedAt = null;
        if (input.receivedAt() != null) {
            try {
                receivedAt = Instant.parse(input.receivedAt());
            } catch (DateTimeParseException exception) {
                return Validation.reject("INVALID_RECEIVED_AT", "receivedAt은 ISO-8601 UTC 시각이어야 합니다.");
            }
        }
        DataMode dataMode = null;
        if (input.dataMode() != null) {
            try {
                dataMode = DataMode.valueOf(input.dataMode());
            } catch (IllegalArgumentException exception) {
                return Validation.reject("INVALID_DATA_MODE", "dataMode는 RAW 또는 FILTERED여야 합니다.");
            }
        }
        Integer flags = input.flags();
        if (flags != null && (flags < 0 || flags > MAX_FLAGS)) {
            return Validation.reject("INVALID_FLAGS", "flags는 0에서 255 사이여야 합니다.");
        }
        if ((input.accelMg() == null) != (input.gyroDps10() == null)
                || !validImuVector(input.accelMg()) || !validImuVector(input.gyroDps10())) {
            return Validation.reject("INVALID_IMU", "accelMg와 gyroDps10은 함께 있어야 하며 int16 3축 배열이어야 합니다.");
        }
        Boolean imuAvailable = input.imuAvailable();
        if (flags != null && (flags & FLAG_IMU_ERROR) != 0) {
            imuAvailable = Boolean.FALSE;
        }
        return Validation.accept(new PressureFrameData(deviceId, side, input.sequence(), input.deviceTimeMs(),
                input.sensorValues(), input.protocolVersion(), receivedAt, dataMode, input.calibrated(),
                imuAvailable, input.accelMg(), input.gyroDps10(), flags));
    }

    private static boolean validImuVector(List<Integer> vector) {
        if (vector == null) return true;
        if (vector.size() != IMU_AXES) return false;
        for (Integer value : vector) {
            if (value == null || value < INT16_MIN || value > INT16_MAX) return false;
        }
        return true;
    }

    private record Validation(PressureFrameData frame, String rejectionCode, String message) {
        static Validation accept(PressureFrameData frame) { return new Validation(frame, null, null); }
        static Validation reject(String code, String message) { return new Validation(null, code, message); }
    }
}
