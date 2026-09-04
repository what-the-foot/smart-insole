package com.smartinsole.measurement;

import com.smartinsole.global.common.DomainTypes.FootSide;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class IngestionDtos {
    private IngestionDtos() {
    }

    public record FrameBatchRequest(
            String schemaVersion,
            String receiverId,
            Instant sentAt,
            List<PressureFrameInput> frames
    ) {
    }

    public record PressureFrameInput(
            String deviceId,
            String footSide,
            Long sequence,
            Long deviceTimeMs,
            List<Integer> sensorValues
    ) {
    }

    public record PressureFrameData(
            UUID deviceId,
            FootSide footSide,
            long sequence,
            long deviceTimeMs,
            List<Integer> sensorValues
    ) {
        public PressureFrameData {
            sensorValues = List.copyOf(sensorValues);
        }
    }

    public record FrameRejection(int frameIndex, String code, String message) {
    }

    public record FrameBatchResponse(
            int acceptedCount,
            int duplicateCount,
            int rejectedCount,
            List<FrameRejection> rejections,
            Map<UUID, Long> lastSequenceByDevice,
            Instant receivedAt
    ) {
    }

    public record FramesPersistedEvent(UUID sessionId, List<PressureFrameData> frames, Instant receivedAt) {
        public FramesPersistedEvent {
            frames = List.copyOf(frames);
        }
    }
}
