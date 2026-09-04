package com.smartinsole.measurement;

import com.smartinsole.global.common.DomainTypes.DataMode;
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

    /**
     * Validated frame ready for persistence. The 1.1 metadata fields are null for schemaVersion 1.0
     * batches; the five-argument constructor keeps the 1.0 call sites and tests unchanged.
     */
    public record PressureFrameData(
            UUID deviceId,
            FootSide footSide,
            long sequence,
            long deviceTimeMs,
            List<Integer> sensorValues,
            Integer protocolVersion,
            Instant receiverReceivedAt,
            DataMode dataMode,
            Boolean calibrated,
            Boolean imuAvailable,
            List<Integer> accelMg,
            List<Integer> gyroDps10,
            Integer flags
    ) {
        public PressureFrameData {
            sensorValues = List.copyOf(sensorValues);
            accelMg = accelMg == null ? null : List.copyOf(accelMg);
            gyroDps10 = gyroDps10 == null ? null : List.copyOf(gyroDps10);
        }

        public PressureFrameData(UUID deviceId, FootSide footSide, long sequence, long deviceTimeMs,
                                 List<Integer> sensorValues) {
            this(deviceId, footSide, sequence, deviceTimeMs, sensorValues, null, null, null, null, null, null,
                    null, null);
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
