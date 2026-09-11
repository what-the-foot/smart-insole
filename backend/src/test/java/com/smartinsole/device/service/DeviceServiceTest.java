package com.smartinsole.device.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.calibration.repository.CalibrationProfileRepository;
import com.smartinsole.device.domain.Device;
import com.smartinsole.device.domain.SensorLayout;
import com.smartinsole.device.dto.DeviceDtos.DeviceHeartbeatRequest;
import com.smartinsole.device.dto.DeviceDtos.DeviceResponse;
import com.smartinsole.device.dto.DeviceDtos.RegisterDeviceRequest;
import com.smartinsole.device.dto.DeviceDtos.SensorLayoutResponse;
import com.smartinsole.device.dto.DeviceDtos;
import com.smartinsole.device.repository.DeviceRepository;
import com.smartinsole.device.repository.SensorLayoutRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeviceServiceTest {
    private static final String ACTIVE_LAYOUT = "layout-s01s08-v1";
    private static final String RETIRED_LAYOUT = "layout-v1";
    private final Instant now = Instant.parse("2026-09-04T01:00:00Z");
    private final UUID userId = UUID.randomUUID();
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final SensorLayoutRepository layouts = mock(SensorLayoutRepository.class);
    private final CalibrationProfileRepository calibrations = mock(CalibrationProfileRepository.class);
    private final DeviceService service = new DeviceService(devices, layouts, calibrations, new ObjectMapper(),
            Clock.fixed(now, ZoneOffset.UTC));

    @BeforeEach
    void setUp() {
        when(layouts.findById(ACTIVE_LAYOUT)).thenReturn(Optional.of(new SensorLayout(ACTIVE_LAYOUT, 8,
                "[{\"index\":0,\"label\":\"S01\",\"x\":0.40,\"y\":0.88,\"region\":\"HEEL\",\"medialLateral\":\"MEDIAL\"}"
                        + ",{\"index\":1,\"label\":\"S02\",\"x\":0.62,\"y\":0.88,\"region\":\"HEEL\",\"medialLateral\":\"LATERAL\"}"
                        + ",{\"index\":2,\"label\":\"S03\",\"x\":0.36,\"y\":0.62,\"region\":\"MIDFOOT\",\"medialLateral\":\"MEDIAL\"}"
                        + ",{\"index\":3,\"label\":\"S04\",\"x\":0.66,\"y\":0.62,\"region\":\"MIDFOOT\",\"medialLateral\":\"LATERAL\"}"
                        + ",{\"index\":4,\"label\":\"S05\",\"x\":0.32,\"y\":0.36,\"region\":\"FOREFOOT\",\"medialLateral\":\"MEDIAL\"}"
                        + ",{\"index\":5,\"label\":\"S06\",\"x\":0.50,\"y\":0.34,\"region\":\"FOREFOOT\",\"medialLateral\":\"CENTER\"}"
                        + ",{\"index\":6,\"label\":\"S07\",\"x\":0.70,\"y\":0.38,\"region\":\"FOREFOOT\",\"medialLateral\":\"LATERAL\"}"
                        + ",{\"index\":7,\"label\":\"S08\",\"x\":0.36,\"y\":0.12,\"region\":\"TOE\",\"medialLateral\":\"MEDIAL\"}]",
                true, now)));
        when(layouts.findById(RETIRED_LAYOUT)).thenReturn(Optional.of(new SensorLayout(RETIRED_LAYOUT, 8,
                "[{\"index\":0,\"x\":0.5,\"y\":0.9,\"region\":\"HEEL\",\"medialLateral\":\"CENTER\"}"
                        + ",{\"index\":1,\"x\":0.36,\"y\":0.68,\"region\":\"MIDFOOT\",\"medialLateral\":\"MEDIAL\"}"
                        + ",{\"index\":2,\"x\":0.64,\"y\":0.68,\"region\":\"MIDFOOT\",\"medialLateral\":\"LATERAL\"}"
                        + ",{\"index\":3,\"x\":0.3,\"y\":0.42,\"region\":\"FOREFOOT\",\"medialLateral\":\"MEDIAL\"}"
                        + ",{\"index\":4,\"x\":0.5,\"y\":0.4,\"region\":\"FOREFOOT\",\"medialLateral\":\"CENTER\"}"
                        + ",{\"index\":5,\"x\":0.7,\"y\":0.42,\"region\":\"FOREFOOT\",\"medialLateral\":\"LATERAL\"}"
                        + ",{\"index\":6,\"x\":0.4,\"y\":0.15,\"region\":\"TOE\",\"medialLateral\":\"MEDIAL\"}"
                        + ",{\"index\":7,\"x\":0.6,\"y\":0.15,\"region\":\"TOE\",\"medialLateral\":\"LATERAL\"}]",
                false, now)));
        when(devices.existsBySerialNumber(anyString())).thenReturn(false);
        when(devices.saveAndFlush(any(Device.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(calibrations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void registersOnTheSupportedAdcScaleWhenAdcMaxIsOmitted() {
        DeviceResponse response = service.register(userId, new RegisterDeviceRequest("SMART-INSOLE-L-12345678",
                "Left", FootSide.LEFT, 8, ACTIVE_LAYOUT, "0.2.0"));

        assertThat(response.adcMax()).isEqualTo(4095);
        assertThat(response.lastBatteryPercent()).isNull();
        assertThat(response.lastBatteryMv()).isNull();
        assertThat(response.activeCalibrationVersion()).isEqualTo("identity-v1");
    }

    @Test
    void rejectsEveryAdcScaleOtherThan4095() {
        assertThatThrownBy(() -> service.register(userId, new RegisterDeviceRequest("SMART-INSOLE-L-12345678",
                "Left", FootSide.LEFT, 8, ACTIVE_LAYOUT, "0.2.0", 1023)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.SEMANTIC_VALIDATION_FAILED);
                    assertThat(error.details()).containsEntry("supportedAdcMax", 4095);
                });
        assertThat(service.register(userId, new RegisterDeviceRequest("SMART-INSOLE-L-12345678", "Left",
                FootSide.LEFT, 8, ACTIVE_LAYOUT, "0.2.0", 4095)).adcMax()).isEqualTo(4095);
    }

    @Test
    void returnsRetiredLayoutsButOnlyRegistersOnActiveOnes() {
        SensorLayoutResponse retired = service.layout(RETIRED_LAYOUT);
        SensorLayoutResponse active = service.layout(ACTIVE_LAYOUT);

        assertThat(retired.points()).hasSize(8).allSatisfy(point -> assertThat(point.label()).isNull());
        assertThat(active.points()).extracting(DeviceDtos.SensorPoint::label)
                .containsExactly("S01", "S02", "S03", "S04", "S05", "S06", "S07", "S08");
        assertThatThrownBy(() -> service.register(userId, new RegisterDeviceRequest("INSOLE-L-001", "Left",
                FootSide.LEFT, 8, RETIRED_LAYOUT, "0.1.0")))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_SENSOR_LAYOUT));
        assertThatThrownBy(() -> service.layout("layout-missing"))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND));
    }

    @Test
    void heartbeatStoresBatteryAndFirmwareOnlyForAcceptedReports() {
        Device device = Device.register(userId, "SMART-INSOLE-L-12345678", "Left", FootSide.LEFT, 8, ACTIVE_LAYOUT,
                "0.1.0", 4095, now);
        when(devices.findByIdForUpdate(device.getId())).thenReturn(Optional.of(device));

        service.heartbeat(device.getId(), new DeviceHeartbeatRequest("GATEWAY-DEV-001", now.plusSeconds(5), true,
                80.0, 3900, "0.2.0", -60));
        service.heartbeat(device.getId(), new DeviceHeartbeatRequest("GATEWAY-DEV-001", now.plusSeconds(1), false,
                10.0, 3400, "0.1.9", -70));
        service.heartbeat(device.getId(), new DeviceHeartbeatRequest("GATEWAY-DEV-001", now.plusSeconds(10), true,
                null, null, null, -61));

        assertThat(device.getLastBatteryPercent()).isEqualTo(80.0);
        assertThat(device.getLastBatteryMv()).isEqualTo(3900);
        assertThat(device.getFirmwareVersion()).isEqualTo("0.2.0");
        assertThat(device.getLastSeenAt()).isEqualTo(now.plusSeconds(10));
    }

    @Test
    void rejectsInvalidHeartbeatBatteryValues() {
        Device device = Device.register(userId, "SMART-INSOLE-L-12345678", "Left", FootSide.LEFT, 8, ACTIVE_LAYOUT,
                "0.1.0", 4095, now);
        when(devices.findByIdForUpdate(device.getId())).thenReturn(Optional.of(device));

        assertThatThrownBy(() -> service.heartbeat(device.getId(), new DeviceHeartbeatRequest("GATEWAY-DEV-001",
                now, true, null, -1, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.heartbeat(device.getId(), new DeviceHeartbeatRequest("GATEWAY-DEV-001",
                now, true, 101.0, null, null, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
        assertThatThrownBy(() -> service.heartbeat(device.getId(), new DeviceHeartbeatRequest("GATEWAY-DEV-001",
                now, true, null, null, "   ", null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_REQUEST));
    }
}
