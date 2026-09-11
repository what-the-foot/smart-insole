package com.smartinsole.measurement.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.measurement.domain.MeasurementQualityStats;
import com.smartinsole.measurement.domain.MeasurementSession;
import com.smartinsole.measurement.repository.MeasurementQualityRepository;
import com.smartinsole.measurement.repository.PressureFrameRepository.SideCoverage;
import com.smartinsole.measurement.dto.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.repository.PressureFrameRepository;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.smartinsole.support.TestSessions;
import com.smartinsole.support.TestIngestionProperties;
import com.smartinsole.measurement.domain.MeasurementQualityStats.SideCursor;
import com.smartinsole.global.common.DomainTypes.ReceiverUploadState;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class QualityServiceTest {
    @Test
    void removesSequenceGapFlagWhenLateDataClosesTheGap() {
        ObjectMapper objectMapper = new ObjectMapper();
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementQualityStats stats = MeasurementQualityStats.create(UUID.randomUUID(), now);

        stats.apply(2, 0, 0, new SideCursor(1, 3, 30), null, Set.of(), objectMapper, now);
        assertThat(stats.getSequenceGapCount()).isEqualTo(1);
        assertThat(stats.flags(objectMapper)).contains("SEQUENCE_GAP");

        stats.apply(1, 0, 0, new SideCursor(2, null, null), null, Set.of(), objectMapper, now.plusMillis(10));

        assertThat(stats.getSequenceGapCount()).isZero();
        assertThat(stats.flags(objectMapper)).doesNotContain("SEQUENCE_GAP");
    }

    @Test
    void detectsAConstant4095SensorWhileOtherSensorsChange() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now);
        UUID sessionId = session.getId();
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
                new RealtimeProperties(10, Duration.ofSeconds(2)), TestIngestionProperties.defaults());

        MeasurementQualityStats result = service.update(session, samples, samples.size(), 0, 0, now);

        assertThat(result.flags(objectMapper)).contains("SENSOR_STUCK_OR_SATURATED");
    }

    @Test
    void doesNotTreatAnUnloadedZeroChannelAsAStuckSensor() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now);
        UUID sessionId = session.getId();
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
                new RealtimeProperties(10, Duration.ofSeconds(2)), TestIngestionProperties.defaults());

        MeasurementQualityStats result = service.update(session, samples, samples.size(), 0, 0, now);

        assertThat(result.flags(objectMapper)).doesNotContain("SENSOR_STUCK_OR_SATURATED");
    }

    @Test
    void detectsAStuckSensorAcrossTenSingleFrameBatches() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now);
        UUID sessionId = session.getId();
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
                new RealtimeProperties(10, Duration.ofSeconds(2)), TestIngestionProperties.defaults());

        for (int sequence = 1; sequence <= 10; sequence++) {
            PressureFrameData frame = new PressureFrameData(deviceId, FootSide.LEFT, sequence, sequence * 10L,
                    List.of(4095, 100 + sequence, 200 + sequence, 300 + sequence,
                            400 + sequence, 500 + sequence, 600 + sequence, 700 + sequence));
            persisted.add(frame);
            service.update(session, List.of(frame), 1, 0, 0, now.plusMillis(sequence));
        }

        assertThat(stored.get().flags(objectMapper)).contains("SENSOR_STUCK_OR_SATURATED");
    }

    @Test
    void suspectsASequenceWrapOnlyForProtocolVersion1Frames() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now);
        UUID deviceId = UUID.randomUUID();
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        PressureFrameRepository frames = mock(PressureFrameRepository.class);
        AtomicReference<MeasurementQualityStats> stored = new AtomicReference<>();
        when(qualities.findById(session.getId())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return stored.get();
        });
        QualityService service = new QualityService(qualities, frames, objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)), TestIngestionProperties.defaults());
        List<PressureFrameData> first = new java.util.ArrayList<>();
        for (int index = 0; index < 5; index++) {
            first.add(new PressureFrameData(deviceId, FootSide.LEFT, 100_000 + index, index * 10L, VALUES));
        }
        service.update(session, first, first.size(), 0, 0, now);

        // Sequence drops by more than 60000 while the device clock keeps advancing: a suspected u16 wrap.
        List<PressureFrameData> wrapped = List.of(
                new PressureFrameData(deviceId, FootSide.LEFT, 5, 1_000L, VALUES),
                new PressureFrameData(deviceId, FootSide.LEFT, 6, 1_010L, VALUES));
        MeasurementQualityStats result = service.update(session, wrapped, 2, 0, 0, now.plusSeconds(1));
        assertThat(result.flags(objectMapper)).contains("SEQUENCE_WRAP_SUSPECTED").doesNotContain("OUT_OF_ORDER");
        assertThat(result.getScore()).isLessThan(100);

        // protocolVersion 2 carries a native u32 sequence: the same drop is plain out-of-order data.
        stored.set(null);
        service.update(session, first, first.size(), 0, 0, now);
        List<PressureFrameData> nativeU32 = wrapped.stream().map(frame -> new PressureFrameData(frame.deviceId(),
                frame.footSide(), frame.sequence(), frame.deviceTimeMs(), frame.sensorValues(), 2, null, null,
                null, null, null, null, null)).toList();
        MeasurementQualityStats protocol2 = service.update(session, nativeU32, 2, 0, 0, now.plusSeconds(1));
        assertThat(protocol2.flags(objectMapper)).contains("OUT_OF_ORDER").doesNotContain("SEQUENCE_WRAP_SUSPECTED");
    }

    @Test
    void flagsASampleRateMismatchFromTheMedianDeviceTimeDelta() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now);
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        when(qualities.findById(any())).thenReturn(Optional.empty());
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> invocation.getArgument(0));
        QualityService service = new QualityService(qualities, mock(PressureFrameRepository.class), objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)), TestIngestionProperties.defaults());
        UUID deviceId = UUID.randomUUID();
        List<PressureFrameData> fiftyHertz = new java.util.ArrayList<>();
        List<PressureFrameData> hundredHertz = new java.util.ArrayList<>();
        for (int index = 0; index < 10; index++) {
            fiftyHertz.add(new PressureFrameData(deviceId, FootSide.LEFT, index, index * 20L, VALUES));
            hundredHertz.add(new PressureFrameData(deviceId, FootSide.LEFT, index, index * 10L, VALUES));
        }

        assertThat(service.update(session, fiftyHertz, 10, 0, 0, now).flags(objectMapper))
                .contains("SAMPLE_RATE_MISMATCH");
        assertThat(service.update(session, hundredHertz, 10, 0, 0, now).flags(objectMapper))
                .doesNotContain("SAMPLE_RATE_MISMATCH");
    }

    @Test
    void derivesReportedFlagsFromSchema11FrameMetadata() {
        UUID deviceId = UUID.randomUUID();
        PressureFrameData fsrAndBattery = new PressureFrameData(deviceId, FootSide.LEFT, 1, 20, VALUES, 2, null,
                com.smartinsole.global.common.DomainTypes.DataMode.RAW, false, true, null, null, 0b101);
        PressureFrameData filtered = new PressureFrameData(deviceId, FootSide.LEFT, 2, 40, VALUES, 1, null,
                com.smartinsole.global.common.DomainTypes.DataMode.FILTERED, false, false, null, null, null);

        assertThat(QualityService.reportedFlags(List.of(fsrAndBattery, filtered)))
                .containsExactly("FSR_ERROR_REPORTED", "BATTERY_LOW_REPORTED", "FILTERED_DATA_MODE");
    }

    @Test
    void countsGapsInConstantTimeAndClosesThemWithLateFrames() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now);
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        PressureFrameRepository frames = mock(PressureFrameRepository.class);
        AtomicReference<MeasurementQualityStats> stored = new AtomicReference<>();
        when(qualities.findById(session.getId())).thenAnswer(invocation -> Optional.ofNullable(stored.get()));
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> {
            stored.set(invocation.getArgument(0));
            return stored.get();
        });
        QualityService service = new QualityService(qualities, frames, objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)), TestIngestionProperties.defaults());
        UUID deviceId = UUID.randomUUID();

        service.update(session, frames(deviceId, 1, 5), 5, 0, 0, now);
        assertThat(stored.get().getSequenceGapCount()).isZero();
        service.update(session, frames(deviceId, 8, 10), 3, 0, 0, now.plusMillis(100));
        assertThat(stored.get().getSequenceGapCount()).isEqualTo(2);
        assertThat(stored.get().flags(objectMapper)).contains("SEQUENCE_GAP");
        service.update(session, frames(deviceId, 6, 7), 2, 0, 0, now.plusMillis(200));

        assertThat(stored.get().getSequenceGapCount()).isZero();
        assertThat(stored.get().getExpectedFrameCount()).isEqualTo(10);
        assertThat(stored.get().flags(objectMapper)).contains("OUT_OF_ORDER").doesNotContain("SEQUENCE_GAP");
        verify(frames, never()).countSequenceGaps(any());
    }

    @Test
    void finalizeReconcilesGapsOnceAndReportsAnIncompleteReceiverUpload() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now.minusSeconds(2));
        session.start(now.minusSeconds(2));
        session.recordReceiverStatus("GATEWAY-DEV-001", ReceiverUploadState.STREAMING, 3, now.minusSeconds(1));
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityStats stats = MeasurementQualityStats.create(session.getId(), now.minusSeconds(1));
        stats.apply(200, 0, 0, new SideCursor(0, 99, 990), new SideCursor(0, 99, 990), Set.of(), objectMapper,
                now.minusSeconds(1));
        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        PressureFrameRepository frames = mock(PressureFrameRepository.class);
        when(qualities.findById(session.getId())).thenReturn(Optional.of(stats));
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(frames.summarizeCoverage(session.getId())).thenReturn(List.of(
                new SideCoverage(FootSide.LEFT, 100, 0, 990, now),
                new SideCoverage(FootSide.RIGHT, 100, 0, 990, now)));
        when(frames.countSequenceGaps(session.getId())).thenReturn(3L);
        QualityService service = new QualityService(qualities, frames, objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)), TestIngestionProperties.defaults());

        MeasurementQualityStats result = service.finalizeSession(session, now);

        assertThat(result.getSequenceGapCount()).isEqualTo(3);
        assertThat(result.flags(objectMapper)).contains("RECEIVER_UPLOAD_INCOMPLETE", "SEQUENCE_GAP");
        verify(frames, times(1)).countSequenceGaps(session.getId());
    }

    private static List<PressureFrameData> frames(UUID deviceId, long first, long last) {
        List<PressureFrameData> values = new java.util.ArrayList<>();
        for (long sequence = first; sequence <= last; sequence++) {
            values.add(new PressureFrameData(deviceId, FootSide.LEFT, sequence, sequence * 10, VALUES));
        }
        return values;
    }

    private static final List<Integer> VALUES = List.of(120, 110, 60, 55, 70, 65, 60, 40);

    @Test
    void completionMarksBothSidesIncompleteWhenA_LongSessionOnlyHasOneFramePerSide() {
        Instant now = Instant.parse("2026-09-02T07:10:00Z");
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1", 100,
                null, now.minus(Duration.ofMinutes(10)));
        session.start(now.minus(Duration.ofMinutes(10)));
        ObjectMapper objectMapper = new ObjectMapper();
        MeasurementQualityStats stats = MeasurementQualityStats.create(session.getId(), now.minusSeconds(1));
        stats.apply(2, 0, 0, new SideCursor(1, 1, 0), new SideCursor(1, 1, 0), Set.of(), objectMapper,
                now.minusSeconds(1));

        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        PressureFrameRepository frames = mock(PressureFrameRepository.class);
        when(qualities.findById(session.getId())).thenReturn(Optional.of(stats));
        when(qualities.save(any(MeasurementQualityStats.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(frames.summarizeCoverage(session.getId())).thenReturn(List.of(
                new SideCoverage(FootSide.LEFT, 1, 0, 0, now),
                new SideCoverage(FootSide.RIGHT, 1, 0, 0, now)));
        QualityService service = new QualityService(qualities, frames, objectMapper,
                new RealtimeProperties(10, Duration.ofSeconds(2)), TestIngestionProperties.defaults());

        MeasurementQualityStats result = service.finalizeSession(session, now);

        assertThat(result.getExpectedFrameCount()).isGreaterThan(100_000);
        assertThat(result.getMissingFrameRate()).isGreaterThan(0.99);
        assertThat(result.flags(objectMapper)).contains("LEFT_DATA_INCOMPLETE", "RIGHT_DATA_INCOMPLETE");
        assertThat(result.getLevel()).isEqualTo(QualityLevel.POOR);
    }
}
