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

    /**
     * Frame Batch envelope. {@code batchId} is optional and only meaningful for schemaVersion 1.1;
     * idempotency stays keyed by (session, device, sequence).
     */
    public record FrameBatchRequest(
            String schemaVersion,
            String receiverId,
            String batchId,
            Instant sentAt,
            List<PressureFrameInput> frames
    ) {
        /** schemaVersion 1.0 envelope without batchId. */
        public FrameBatchRequest(String schemaVersion, String receiverId, Instant sentAt,
                                 List<PressureFrameInput> frames) {
            this(schemaVersion, receiverId, null, sentAt, frames);
        }
    }

    /**
     * Raw frame as sent by the receiver. Fields after {@code sensorValues} are the schemaVersion 1.1
     * extensions; they are typed loosely (String/Integer) so that a bad value rejects only that frame
     * with a per-frame code instead of failing the whole request during JSON binding.
     */
    public record PressureFrameInput(
            String deviceId,
            String footSide,
            Long sequence,
            Long deviceTimeMs,
            List<Integer> sensorValues,
            Integer protocolVersion,
            String receivedAt,
            String dataMode,
            Boolean calibrated,
            Boolean imuAvailable,
            List<Integer> accelMg,
            List<Integer> gyroDps10,
            Integer flags
    ) {
        /** schemaVersion 1.0 frame: the five mandatory fields only. */
        public PressureFrameInput(String deviceId, String footSide, Long sequence, Long deviceTimeMs,
                                  List<Integer> sensorValues) {
            this(deviceId, footSide, sequence, deviceTimeMs, sensorValues, null, null, null, null, null, null,
                    null, null);
        }

        public static PressureFrameInput v10(String deviceId, String footSide, Long sequence, Long deviceTimeMs,
                                             List<Integer> sensorValues) {
            return new PressureFrameInput(deviceId, footSide, sequence, deviceTimeMs, sensorValues);
        }

        /** True when any 1.1-only field is present, which a 1.0 batch must not carry. */
        public boolean hasSchema11Fields() {
            return protocolVersion != null || receivedAt != null || dataMode != null || calibrated != null
                    || imuAvailable != null || accelMg != null || gyroDps10 != null || flags != null;
        }
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
