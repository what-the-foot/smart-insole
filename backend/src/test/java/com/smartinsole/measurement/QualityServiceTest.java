package com.smartinsole.measurement;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.measurement.PressureFrameRepository.SideCoverage;
import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.smartinsole.support.TestSessions;

class QualityServiceTest {
    @Test
    void removesSequenceGapFlagWhenLateDataClosesTheGap() {
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementQualityStats stats = MeasurementQualityStats.create(UUID.randomUUID(), now);

        stats.apply(2, 0, 0, 1, 3L, null, 30L, null, Set.of(), objectMapper, now);
        assertThat(stats.flags(objectMapper)).contains("SEQUENCE_GAP");

        stats.apply(1, 0, 0, 0, 3L, null, 30L, null, Set.of(), objectMapper, now.plusMillis(10));

        assertThat(stats.getSequenceGapCount()).isZero();
        assertThat(stats.flags(objectMapper)).doesNotContain("SEQUENCE_GAP");
    }

    @Test
    void detectsAConstant4095SensorWhileOtherSensorsChange() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        UUID sessionId = UUID.randomUUID();
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        PressureFrameRepository frames = mock(PressureFrameRepository.class);
        when(qualities.findById(sessionId)).thenReturn(Optional.empty());
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> invocation.getArgument(0));
        List<PressureFrameData> samples = new java.util.ArrayList<>();
        UUID deviceId = UUID.randomUUID();
        for (int index = 0; index < 12; index++) {
            samples.add(new PressureFrameData(deviceId, FootSide.LEFT, index, index * 10L,
                    List.of(100 + index, 200 + index, 4095, 300 + index, 400 + index, 500 + index,
                            600 + index, 700 + index)));
        }
        QualityService service = new QualityService(qualities, frames, objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)));

        MeasurementQualityStats result = service.update(sessionId, samples, samples.size(), 0, 0, now);

        assertThat(result.flags(objectMapper)).contains("SENSOR_STUCK_OR_SATURATED");
    }

    @Test
    void doesNotTreatAnUnloadedZeroChannelAsAStuckSensor() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        UUID sessionId = UUID.randomUUID();
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        PressureFrameRepository frames = mock(PressureFrameRepository.class);
        when(qualities.findById(sessionId)).thenReturn(Optional.empty());
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> invocation.getArgument(0));
        List<PressureFrameData> samples = new java.util.ArrayList<>();
        UUID deviceId = UUID.randomUUID();
        for (int index = 0; index < 12; index++) {
            samples.add(new PressureFrameData(deviceId, FootSide.LEFT, index, index * 10L,
                    List.of(0, 200 + index, 300 + index, 400 + index,
                            500 + index, 600 + index, 700 + index, 800 + index)));
        }
        QualityService service = new QualityService(qualities, frames, objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)));

        MeasurementQualityStats result = service.update(sessionId, samples, samples.size(), 0, 0, now);

        assertThat(result.flags(objectMapper)).doesNotContain("SENSOR_STUCK_OR_SATURATED");
    }

    @Test
    void detectsAStuckSensorAcrossTenSingleFrameBatches() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        UUID sessionId = UUID.randomUUID();
        UUID deviceId = UUID.randomUUID();
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        PressureFrameRepository frames = mock(PressureFrameRepository.class);
        AtomicReference<MeasurementQualityStats> stored = new AtomicReference<>();
        List<PressureFrameData> persisted = new java.util.ArrayList<>();
        when(qualities.findById(sessionId)).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> {
            MeasurementQualityStats value = invocation.getArgument(0);
            stored.set(value);
            return value;
        });
        when(frames.findRecentForQuality(sessionId, FootSide.LEFT, 20))
                .thenAnswer(invocation -> List.copyOf(persisted.reversed()));
        QualityService service = new QualityService(qualities, frames, objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)));

        for (int sequence = 1; sequence <= 10; sequence++) {
            PressureFrameData frame = new PressureFrameData(deviceId, FootSide.LEFT, sequence, sequence * 10L,
                    List.of(4095, 100 + sequence, 200 + sequence, 300 + sequence,
                            400 + sequence, 500 + sequence, 600 + sequence, 700 + sequence));
            persisted.add(frame);
            service.update(sessionId, List.of(frame), 1, 0, 0, now.plusMillis(sequence));
        }

        assertThat(stored.get().flags(objectMapper)).contains("SENSOR_STUCK_OR_SATURATED");
    }

    @Test
    void completionMarksBothSidesIncompleteWhenA_LongSessionOnlyHasOneFramePerSide() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now.minus(Duration.ofMinutes(10)));
        session.start(now.minus(Duration.ofMinutes(10)));
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityStats stats = MeasurementQualityStats.create(session.getId(), now.minusSeconds(1));
        stats.apply(2, 0, 0, 0, 1L, 1L, 0L, 0L, Set.of(), objectMapper, now.minusSeconds(1));

        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        PressureFrameRepository frames = mock(PressureFrameRepository.class);
        when(qualities.findById(session.getId())).thenReturn(Optional.of(stats));
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(frames.summarizeCoverage(session.getId())).thenReturn(List.of(
                new SideCoverage(FootSide.LEFT, 1, 0, 0, now),
                new SideCoverage(FootSide.RIGHT, 1, 0, 0, now)));
        QualityService service = new QualityService(qualities, frames, objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)));

        MeasurementQualityStats result = service.finalizeSession(session, now);

        assertThat(result.getExpectedFrameCount()).isGreaterThan(100_000);
        assertThat(result.getMissingFrameRate()).isGreaterThan(0.99);
        assertThat(result.flags(objectMapper)).contains("LEFT_DATA_INCOMPLETE", "RIGHT_DATA_INCOMPLETE");
        assertThat(result.getLevel()).isEqualTo(QualityLevel.POOR);
    }
}
