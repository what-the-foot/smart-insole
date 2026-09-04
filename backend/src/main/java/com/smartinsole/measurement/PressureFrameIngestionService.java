package com.smartinsole.measurement;

import com.smartinsole.device.Device;
import com.smartinsole.device.DeviceRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.IngestionDtos.FrameBatchRequest;
import com.smartinsole.measurement.IngestionDtos.FrameBatchResponse;
import com.smartinsole.measurement.IngestionDtos.FrameRejection;
import com.smartinsole.measurement.IngestionDtos.FramesPersistedEvent;
import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.IngestionDtos.PressureFrameInput;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PressureFrameIngestionService {
    private final MeasurementSessionRepository sessions;
    private final DeviceRepository devices;
    private final PressureFrameRepository frames;
    private final QualityService qualityService;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    public PressureFrameIngestionService(MeasurementSessionRepository sessions, DeviceRepository devices,
                                         PressureFrameRepository frames, QualityService qualityService,
                                         ApplicationEventPublisher events, Clock clock) {
        this.sessions = sessions;
        this.devices = devices;
        this.frames = frames;
        this.qualityService = qualityService;
        this.events = events;
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
                throw new BusinessException(ErrorCode.SESSION_NOT_MEASURING,
                        ErrorCode.SESSION_NOT_MEASURING.defaultMessage(),
                        Map.of("currentStatus", session.getStatus().name()));
            }
            Map<UUID, Device> assigned = new HashMap<>();
            assigned.put(session.getLeftDeviceId(), devices.findById(session.getLeftDeviceId()).orElseThrow());
            assigned.put(session.getRightDeviceId(), devices.findById(session.getRightDeviceId()).orElseThrow());

            List<PressureFrameData> valid = new ArrayList<>();
            List<FrameRejection> rejections = new ArrayList<>();
            for (int index = 0; index < request.frames().size(); index++) {
                Validation validation = validateFrame(request.frames().get(index), assigned);
                if (validation.rejectionCode() == null) {
                    valid.add(validation.frame());
                } else {
                    rejections.add(new FrameRejection(index, validation.rejectionCode(), validation.message()));
                }
            }

            Instant receivedAt = Instant.now(clock);
            PressureFrameRepository.BatchInsertResult inserted = frames.insertBatch(sessionId, valid, receivedAt);
            qualityService.update(sessionId, inserted.acceptedFrames(), inserted.acceptedCount(),
                    inserted.duplicateCount(),
                    rejections.size(), receivedAt);
            if (!inserted.acceptedFrames().isEmpty()) {
                events.publishEvent(new FramesPersistedEvent(sessionId, inserted.acceptedFrames(), receivedAt));
            }
            Set<UUID> validDeviceIds = new LinkedHashSet<>();
            valid.forEach(frame -> validDeviceIds.add(frame.deviceId()));
            Map<UUID, Long> lastSequences = frames.findMaxSequences(sessionId, validDeviceIds);
            return new FrameBatchResponse(inserted.acceptedCount(), inserted.duplicateCount(), rejections.size(),
                    List.copyOf(rejections), Map.copyOf(lastSequences), receivedAt);
    }

    private static void validateEnvelope(FrameBatchRequest request) {
        if (request == null || request.receiverId() == null || request.receiverId().isBlank()
                || request.sentAt() == null || request.frames() == null || request.frames().isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (!"1.0".equals(request.schemaVersion())) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_SCHEMA_VERSION);
        }
        if (request.receiverId().length() > 100) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (request.frames().size() > 200) {
            throw new BusinessException(ErrorCode.PAYLOAD_TOO_LARGE);
        }
    }

    private static Validation validateFrame(PressureFrameInput input, Map<UUID, Device> assigned) {
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
        return Validation.accept(new PressureFrameData(deviceId, side, input.sequence(), input.deviceTimeMs(),
                input.sensorValues()));
    }

    private record Validation(PressureFrameData frame, String rejectionCode, String message) {
        static Validation accept(PressureFrameData frame) { return new Validation(frame, null, null); }
        static Validation reject(String code, String message) { return new Validation(null, code, message); }
    }
}
