package com.smartinsole.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartinsole.analysis.AnalysisJobRepository;
import com.smartinsole.calibration.CalibrationProfile;
import com.smartinsole.calibration.CalibrationProfileRepository;
import com.smartinsole.device.Device;
import com.smartinsole.device.DeviceRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.SourceType;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.MeasurementDtos.CreateMeasurementSessionRequest;
import com.smartinsole.measurement.MeasurementDtos.MeasurementSessionResponse;
import com.smartinsole.support.TestAnalysisProperties;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

class MeasurementServiceTest {
    private static final String LAYOUT = "layout-s01s08-v1";
    private final Instant now = Instant.parse("2026-09-04T01:00:00Z");
    private final UUID userId = UUID.randomUUID();
    private final MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final CalibrationProfileRepository calibrations = mock(CalibrationProfileRepository.class);
    private final MeasurementService service = new MeasurementService(sessions, devices, calibrations,
            mock(AnalysisJobRepository.class), TestAnalysisProperties.defaults(),
            mock(ApplicationEventPublisher.class), Clock.fixed(now, ZoneOffset.UTC),
            mock(HistoryProjectionRepository.class), mock(QualityService.class));
    private Device left;
    private Device right;

    @BeforeEach
    void setUp() {
        left = device("SMART-INSOLE-L-12345678", FootSide.LEFT, 4095);
        right = device("SMART-INSOLE-R-9abcdef0", FootSide.RIGHT, 4095);
        when(sessions.save(any(MeasurementSession.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    @Test
    void createsADeviceSessionAt50HzWithTheDeviceAdcScaleByDefault() {
        MeasurementSessionResponse response = service.create(userId,
                new CreateMeasurementSessionRequest(left.getId(), right.getId(), 50, null));

        assertThat(response.sampleRateHz()).isEqualTo(50);
        assertThat(response.sourceType()).isEqualTo(SourceType.DEVICE);
        assertThat(response.adcMax()).isEqualTo(4095);
        assertThat(response.receiverState()).isNull();
        assertThat(response.receiverPendingBatches()).isNull();
    }

    @Test
    void keepsAnExplicitSimulatedSourceTypeAnd100Hz() {
        MeasurementSessionResponse response = service.create(userId,
                new CreateMeasurementSessionRequest(left.getId(), right.getId(), 100, SourceType.SIMULATED, "sim"));

        assertThat(response.sourceType()).isEqualTo(SourceType.SIMULATED);
        assertThat(response.sampleRateHz()).isEqualTo(100);
    }

    @Test
    void rejectsSampleRatesOtherThan50Or100() {
        assertThatThrownBy(() -> service.create(userId,
                new CreateMeasurementSessionRequest(left.getId(), right.getId(), 60, null)))
                .isInstanceOfSatisfying(BusinessException.class, error -> {
                    assertThat(error.code()).isEqualTo(ErrorCode.SEMANTIC_VALIDATION_FAILED);
                    assertThat(error.details()).containsEntry("sampleRateHz", 60);
                });
    }

    @Test
    void rejectsDevicePairsWithDifferentAdcScales() {
        Device legacyRight = device("LEGACY-R-001", FootSide.RIGHT, 1023);

        assertThatThrownBy(() -> service.create(userId,
                new CreateMeasurementSessionRequest(left.getId(), legacyRight.getId(), 50, null)))
                .isInstanceOfSatisfying(BusinessException.class,
                        error -> assertThat(error.code()).isEqualTo(ErrorCode.INVALID_DEVICE_SELECTION));
    }

    private Device device(String serial, FootSide side, int adcMax) {
        Device device = Device.register(userId, serial, serial, side, 8, LAYOUT, "0.2.0", adcMax, now);
        when(devices.findByIdAndUserId(device.getId(), userId)).thenReturn(Optional.of(device));
        when(calibrations.findFirstByDeviceIdAndActiveTrueOrderByCreatedAtDesc(device.getId()))
                .thenReturn(Optional.of(CalibrationProfile.identity(device.getId(),
                        "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", now)));
        return device;
    }
}
