package com.smartinsole.measurement.dto;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.global.common.DomainTypes.ReceiverUploadState;
import com.smartinsole.global.common.DomainTypes.SourceType;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class MeasurementDtos {
    private MeasurementDtos() {
    }

    /** {@code sourceType} is optional and defaults to DEVICE; only simulators send SIMULATED. */
    public record CreateMeasurementSessionRequest(
            @NotNull UUID leftDeviceId,
            @NotNull UUID rightDeviceId,
            @NotNull Integer sampleRateHz,
            SourceType sourceType,
            @Size(max = 500) String memo
    ) {
        public CreateMeasurementSessionRequest(UUID leftDeviceId, UUID rightDeviceId, Integer sampleRateHz,
                                               String memo) {
            this(leftDeviceId, rightDeviceId, sampleRateHz, null, memo);
        }
    }

    public record MeasurementSessionResponse(
            UUID sessionId,
            MeasurementStatus status,
            UUID leftDeviceId,
            UUID rightDeviceId,
            int sampleRateHz,
            SourceType sourceType,
            int adcMax,
            String memo,
            Integer dataQualityScore,
            Instant startedAt,
            Instant endedAt,
            Instant createdAt,
            ReceiverUploadState receiverState,
            Integer receiverPendingBatches
    ) {
    }

    public record MeasurementHistoryItem(
            UUID sessionId,
            MeasurementStatus status,
            UUID leftDeviceId,
            UUID rightDeviceId,
            int sampleRateHz,
            SourceType sourceType,
            String memo,
            Integer dataQualityScore,
            Instant startedAt,
            Instant endedAt,
            Instant createdAt,
            String primaryPatternCode,
            // Contract 1.2.0 summary metrics of the session's latest analysis result (same latest-result rule as
            // primaryPatternCode). All nullable: null without a result, and per field for results older than the
            // version that introduced it (validStepCount rule-v1.1.0; load share and stride time rule-v1.3.0).
            String algorithmVersion,
            QualityLevel dataQualityLevel,
            Double symmetryIndex,
            Double cadence,
            Double leftContactTimeMs,
            Double rightContactTimeMs,
            Integer validStepCount,
            Double leftLoadSharePct,
            Double rightLoadSharePct,
            Double meanStrideTimeMs
    ) {
    }

    public record MeasurementSessionPage(
            List<MeasurementHistoryItem> items,
            int page,
            int size,
            long totalElements,
            int totalPages
    ) {
    }

    public record SessionCompletedEvent(UUID sessionId) {
    }

    public record SessionClosedEvent(UUID sessionId) {
    }
}
