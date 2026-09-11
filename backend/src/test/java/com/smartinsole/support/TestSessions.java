package com.smartinsole.support;

import com.smartinsole.global.common.DomainTypes.SourceType;
import com.smartinsole.measurement.domain.MeasurementSession;
import java.time.Instant;
import java.util.UUID;

/**
 * Test factory for {@link MeasurementSession} so that entity constructor changes (sourceType, adcMax)
 * are absorbed in one place instead of every test.
 */
public final class TestSessions {
    public static final int ADC_MAX = 4095;

    private TestSessions() {
    }

    public static MeasurementSession create(UUID userId, UUID leftDeviceId, UUID rightDeviceId,
                                            UUID leftCalibrationId, UUID rightCalibrationId,
                                            String leftLayout, String rightLayout, int sampleRateHz,
                                            String memo, Instant now) {
        return MeasurementSession.create(userId, leftDeviceId, rightDeviceId, leftCalibrationId,
                rightCalibrationId, leftLayout, rightLayout, sampleRateHz, SourceType.SIMULATED, ADC_MAX, memo, now);
    }

    public static MeasurementSession create(UUID userId, UUID leftDeviceId, UUID rightDeviceId,
                                            UUID leftCalibrationId, UUID rightCalibrationId,
                                            String leftLayout, String rightLayout, int sampleRateHz,
                                            SourceType sourceType, int adcMax, String memo, Instant now) {
        return MeasurementSession.create(userId, leftDeviceId, rightDeviceId, leftCalibrationId,
                rightCalibrationId, leftLayout, rightLayout, sampleRateHz, sourceType, adcMax, memo, now);
    }
}
