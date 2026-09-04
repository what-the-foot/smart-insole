package com.smartinsole.realtime;

import com.smartinsole.global.common.DomainTypes.ContactState;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class RealtimeDtos {
    private RealtimeDtos() {
    }

    public record CopPoint(double x, double y) {
    }

    public record FootRealtimeData(
            boolean connected,
            long lastSequence,
            long deviceTimeMs,
            List<Double> sensorValues,
            double totalPressure,
            CopPoint cop,
            ContactState contactState,
            Instant lastReceivedAt
    ) {
    }

    public record RealtimeQuality(int score, QualityLevel level, List<String> flags) {
    }

    public record RealtimePressureMessage(
            String schemaVersion,
            UUID sessionId,
            Instant serverTime,
            long elapsedTimeMs,
            String status,
            FootRealtimeData left,
            FootRealtimeData right,
            RealtimeQuality quality
    ) {
    }
}
