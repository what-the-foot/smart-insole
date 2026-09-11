package com.smartinsole.measurement.service;

import com.smartinsole.device.domain.Device;
import com.smartinsole.device.repository.DeviceRepository;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.dto.IngestionDtos.ReceiverSessionDevice;
import com.smartinsole.measurement.dto.IngestionDtos.ReceiverSessionListResponse;
import com.smartinsole.measurement.dto.IngestionDtos.ReceiverSessionResponse;
import com.smartinsole.measurement.repository.MeasurementSessionRepository;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Read model for the BLE receiver: which devices a session is bound to, its ADC scale and sample rate,
 * and the sessions currently measuring. Authenticated with X-Receiver-Key, so no user ownership check.
 */
@Service
public class ReceiverSessionQueryService {
    private final MeasurementSessionRepository sessions;
    private final DeviceRepository devices;

    public ReceiverSessionQueryService(MeasurementSessionRepository sessions, DeviceRepository devices) {
        this.sessions = sessions;
        this.devices = devices;
    }

    @Transactional(readOnly = true)
    public ReceiverSessionResponse get(UUID sessionId) {
        MeasurementSession session = sessions.findById(sessionId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        return response(session, loadDevices(List.of(session)));
    }

    @Transactional(readOnly = true)
    public ReceiverSessionListResponse list(MeasurementStatus status, String deviceSerial) {
        if (status != MeasurementStatus.MEASURING) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST, "status는 MEASURING만 허용합니다.",
                    Map.of("status", status == null ? "null" : status.name()));
        }
        List<MeasurementSession> found;
        if (deviceSerial == null || deviceSerial.isBlank()) {
            found = sessions.findAllByStatusOrderByCreatedAtDesc(MeasurementStatus.MEASURING);
        } else {
            Device device = devices.findBySerialNumber(deviceSerial.trim()).orElse(null);
            if (device == null) {
                return new ReceiverSessionListResponse(List.of());
            }
            found = sessions.findAllByStatusAndDevice(MeasurementStatus.MEASURING, device.getId());
        }
        Map<UUID, Device> assigned = loadDevices(found);
        return new ReceiverSessionListResponse(found.stream().map(session -> response(session, assigned)).toList());
    }

    private Map<UUID, Device> loadDevices(List<MeasurementSession> found) {
        Set<UUID> ids = new LinkedHashSet<>();
        for (MeasurementSession session : found) {
            ids.add(session.getLeftDeviceId());
            ids.add(session.getRightDeviceId());
        }
        Map<UUID, Device> byId = new HashMap<>();
        if (!ids.isEmpty()) {
            devices.findAllById(ids).forEach(device -> byId.put(device.getId(), device));
        }
        return byId;
    }

    static ReceiverSessionResponse response(MeasurementSession session, Map<UUID, Device> assigned) {
        return new ReceiverSessionResponse(session.getId(), session.getStatus(), session.getSampleRateHz(),
                session.getSourceType(), session.getAdcMax(), session.getStartedAt(), session.getEndedAt(),
                device(session.getLeftDeviceId(), assigned), device(session.getRightDeviceId(), assigned),
                session.getReceiverState(), session.getReceiverPendingBatches());
    }

    private static ReceiverSessionDevice device(UUID deviceId, Map<UUID, Device> assigned) {
        Device device = assigned.get(deviceId);
        if (device == null) {
            throw new IllegalStateException("Session references a missing device " + deviceId);
        }
        return new ReceiverSessionDevice(device.getId(), device.getSerialNumber(), device.getFootSide(),
                device.getSensorCount(), device.getSensorLayoutVersion(), device.getAdcMax(),
                device.getFirmwareVersion());
    }
}
