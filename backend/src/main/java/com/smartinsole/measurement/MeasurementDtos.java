package com.smartinsole.measurement;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
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
            String primaryPatternCode
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
