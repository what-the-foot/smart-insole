package com.smartinsole.measurement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartinsole.analysis.repository.AnalysisJobRepository;
import com.smartinsole.calibration.domain.CalibrationProfile;
import com.smartinsole.calibration.repository.CalibrationProfileRepository;
import com.smartinsole.device.domain.Device;
import com.smartinsole.device.repository.DeviceRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.global.common.DomainTypes.SourceType;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.dto.MeasurementDtos.CreateMeasurementSessionRequest;
import com.smartinsole.measurement.dto.MeasurementDtos.MeasurementHistoryItem;
import com.smartinsole.measurement.dto.MeasurementDtos.MeasurementSessionPage;
import com.smartinsole.measurement.dto.MeasurementDtos.MeasurementSessionResponse;
import com.smartinsole.measurement.repository.HistoryProjectionRepository;
import com.smartinsole.measurement.repository.HistoryProjectionRepository.LatestResultSummary;
import com.smartinsole.measurement.repository.MeasurementSessionRepository;
import com.smartinsole.support.TestAnalysisProperties;
import com.smartinsole.support.TestSessions;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;

class MeasurementServiceTest {
    private static final String LAYOUT = "layout-s01s08-v1";
    private final Instant now = Instant.parse("2026-09-04T01:00:00Z");
    private final UUID userId = UUID.randomUUID();
    private final MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
    private final DeviceRepository devices = mock(DeviceRepository.class);
    private final CalibrationProfileRepository calibrations = mock(CalibrationProfileRepository.class);
    private final HistoryProjectionRepository historyProjections = mock(HistoryProjectionRepository.class);
    private final MeasurementService service = new MeasurementService(sessions, devices, calibrations,
            mock(AnalysisJobRepository.class), TestAnalysisProperties.defaults(),
            mock(ApplicationEventPublisher.class), Clock.fixed(now, ZoneOffset.UTC),
            historyProjections, mock(QualityService.class));
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

    @Test
    void historyListsTheLatestResultSummaryPerSessionAndNullsForSessionsWithoutOne() {
        MeasurementSession completed = TestSessions.create(userId, left.getId(), right.getId(), UUID.randomUUID(),
                UUID.randomUUID(), LAYOUT, LAYOUT, 100, "done", now.minusSeconds(60));
        completed.start(now.minusSeconds(50));
        completed.complete(now.minusSeconds(40));
        completed.analysisCompleted(88, now.minusSeconds(30));
        MeasurementSession measuring = TestSessions.create(userId, left.getId(), right.getId(), UUID.randomUUID(),
                UUID.randomUUID(), LAYOUT, LAYOUT, 50, null, now.minusSeconds(5));
        measuring.start(now);
        PageRequest pageable = PageRequest.of(0, 20);
        when(sessions.searchHistory(userId.toString(), null, null, null, null, null, pageable))
                .thenReturn(new PageImpl<>(List.of(measuring, completed), pageable, 2));
        when(historyProjections.latestResults(List.of(measuring.getId(), completed.getId())))
                .thenReturn(Map.of(completed.getId(), new LatestResultSummary("LEFT_RIGHT_ASYMMETRY",
                        "rule-v1.3.0", QualityLevel.GOOD, 6.5, 108.0, 640.0, 610.0, 8, 47.0, 53.0, 1090.0)));

        MeasurementSessionPage page = service.list(userId, 0, 20, null, null, null, null, null);

        assertThat(page.totalElements()).isEqualTo(2);
        MeasurementHistoryItem first = page.items().get(0);
        assertThat(first.sessionId()).isEqualTo(measuring.getId());
        assertThat(first.status()).isEqualTo(MeasurementStatus.MEASURING);
        assertThat(first.primaryPatternCode()).isNull();
        assertThat(first.algorithmVersion()).isNull();
        assertThat(first.dataQualityLevel()).isNull();
        assertThat(first.symmetryIndex()).isNull();
        assertThat(first.cadence()).isNull();
        assertThat(first.leftContactTimeMs()).isNull();
        assertThat(first.rightContactTimeMs()).isNull();
        assertThat(first.validStepCount()).isNull();
        assertThat(first.leftLoadSharePct()).isNull();
        assertThat(first.rightLoadSharePct()).isNull();
        assertThat(first.meanStrideTimeMs()).isNull();
        MeasurementHistoryItem second = page.items().get(1);
        assertThat(second.sessionId()).isEqualTo(completed.getId());
        assertThat(second.status()).isEqualTo(MeasurementStatus.COMPLETED);
        assertThat(second.dataQualityScore()).isEqualTo(88);
        assertThat(second.primaryPatternCode()).isEqualTo("LEFT_RIGHT_ASYMMETRY");
        assertThat(second.algorithmVersion()).isEqualTo("rule-v1.3.0");
        assertThat(second.dataQualityLevel()).isEqualTo(QualityLevel.GOOD);
        assertThat(second.symmetryIndex()).isEqualTo(6.5);
        assertThat(second.cadence()).isEqualTo(108.0);
        assertThat(second.leftContactTimeMs()).isEqualTo(640.0);
        assertThat(second.rightContactTimeMs()).isEqualTo(610.0);
        assertThat(second.validStepCount()).isEqualTo(8);
        assertThat(second.leftLoadSharePct()).isEqualTo(47.0);
        assertThat(second.rightLoadSharePct()).isEqualTo(53.0);
        assertThat(second.meanStrideTimeMs()).isEqualTo(1090.0);
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
