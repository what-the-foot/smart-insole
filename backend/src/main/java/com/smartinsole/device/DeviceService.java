package com.smartinsole.device;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.calibration.CalibrationProfile;
import com.smartinsole.calibration.CalibrationProfileRepository;
import com.smartinsole.device.DeviceDtos.DeviceHeartbeatRequest;
import com.smartinsole.device.DeviceDtos.DeviceResponse;
import com.smartinsole.device.DeviceDtos.RegisterDeviceRequest;
import com.smartinsole.device.DeviceDtos.SensorLayoutResponse;
import com.smartinsole.device.DeviceDtos.SensorPoint;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeviceService {
    /** The only ADC scale accepted at registration (12-bit RAW, 4095 = saturation). */
    public static final int SUPPORTED_ADC_MAX = 4095;
    private final DeviceRepository devices;
    private final SensorLayoutRepository layouts;
    private final CalibrationProfileRepository calibrations;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public DeviceService(DeviceRepository devices, SensorLayoutRepository layouts,
                         CalibrationProfileRepository calibrations, ObjectMapper objectMapper, Clock clock) {
        this.devices = devices;
        this.layouts = layouts;
        this.calibrations = calibrations;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    @Transactional
    public DeviceResponse register(UUID userId, RegisterDeviceRequest request) {
        int sensorCount = request.sensorCount();
        if (sensorCount != 6 && sensorCount != 8) {
            throw new BusinessException(ErrorCode.INVALID_SENSOR_LAYOUT);
        }
        if (request.adcMax() != null && request.adcMax() != SUPPORTED_ADC_MAX) {
            throw new BusinessException(ErrorCode.SEMANTIC_VALIDATION_FAILED,
                    "adcMax는 " + SUPPORTED_ADC_MAX + "만 허용합니다.",
                    Map.of("adcMax", request.adcMax(), "supportedAdcMax", SUPPORTED_ADC_MAX));
        }
        SensorLayout layout = layouts.findById(request.sensorLayoutVersion())
                .filter(SensorLayout::isActive)
                .orElseThrow(() -> new BusinessException(ErrorCode.INVALID_SENSOR_LAYOUT));
        if (layout.getSensorCount() != sensorCount) {
            throw new BusinessException(ErrorCode.INVALID_SENSOR_LAYOUT);
        }
        String serial = request.serialNumber().trim();
        if (devices.existsBySerialNumber(serial)) {
            throw new BusinessException(ErrorCode.SERIAL_NUMBER_ALREADY_EXISTS);
        }
        Instant now = Instant.now(clock);
        Device device = Device.register(userId, serial, request.displayName().trim(), request.footSide(),
                sensorCount, layout.getVersion(), request.firmwareVersion().trim(), SUPPORTED_ADC_MAX, now);
        try {
            devices.saveAndFlush(device);
        } catch (DataIntegrityViolationException exception) {
            throw new BusinessException(ErrorCode.SERIAL_NUMBER_ALREADY_EXISTS);
        }
        CalibrationProfile calibration = calibrations.save(CalibrationProfile.identity(device.getId(),
                numericArray(sensorCount, 0.0), numericArray(sensorCount, 1.0), now));
        return response(device, calibration.getVersion());
    }

    @Transactional(readOnly = true)
    public List<DeviceResponse> list(UUID userId) {
        List<Device> ownedDevices = devices.findAllByUserIdOrderByRegisteredAtDesc(userId);
        if (ownedDevices.isEmpty()) {
            return List.of();
        }
        Map<UUID, CalibrationProfile> activeCalibrations = calibrations
                .findAllByDeviceIdInAndActiveTrue(ownedDevices.stream().map(Device::getId).toList()).stream()
                .collect(Collectors.toMap(CalibrationProfile::getDeviceId, Function.identity(),
                        (first, second) -> Comparator.comparing(CalibrationProfile::getCreatedAt)
                                .compare(first, second) >= 0 ? first : second));
        return ownedDevices.stream().map(device -> response(device,
                activeCalibrations.containsKey(device.getId())
                        ? activeCalibrations.get(device.getId()).getVersion() : null)).toList();
    }

    /**
     * Returns the layout regardless of its active flag: devices registered on a retired layout must
     * keep rendering. Only {@link #register} requires an active layout.
     */
    @Transactional(readOnly = true)
    public SensorLayoutResponse layout(String version) {
        SensorLayout layout = layouts.findById(version)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        try {
            List<SensorPoint> points = objectMapper.readValue(layout.getPointsJson(), new TypeReference<>() { });
            return new SensorLayoutResponse(layout.getVersion(), layout.getSensorCount(), points);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Stored sensor layout JSON is invalid", exception);
        }
    }

    @Transactional
    public void heartbeat(UUID deviceId, DeviceHeartbeatRequest request) {
        if (request.batteryPercent() != null
                && (request.batteryPercent() < 0 || request.batteryPercent() > 100)) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (request.batteryMv() != null && request.batteryMv() < 0) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        if (request.firmwareVersion() != null && request.firmwareVersion().isBlank()) {
            throw new BusinessException(ErrorCode.INVALID_REQUEST);
        }
        Device device = devices.findByIdForUpdate(deviceId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (device.heartbeat(request.connected(), request.observedAt())) {
            device.recordBattery(request.batteryPercent(), request.batteryMv());
            device.recordFirmwareVersion(request.firmwareVersion());
        }
    }

    private String numericArray(int count, double value) {
        List<Double> values = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            values.add(value);
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private DeviceResponse response(Device device, String activeCalibrationVersion) {
        return new DeviceResponse(device.getId(), device.getSerialNumber(), device.getDisplayName(),
                device.getFootSide(), device.getSensorCount(), device.getSensorLayoutVersion(),
                activeCalibrationVersion, device.getFirmwareVersion(), device.getAdcMax(), device.getStatus(),
                device.getLastSeenAt(), device.getLastBatteryPercent(), device.getLastBatteryMv(),
                device.getRegisteredAt());
    }
}
