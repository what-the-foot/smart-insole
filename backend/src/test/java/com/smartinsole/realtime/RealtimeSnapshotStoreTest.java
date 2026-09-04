package com.smartinsole.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.smartinsole.calibration.CalibrationProfile;
import com.smartinsole.calibration.CalibrationProfileRepository;
import com.smartinsole.device.SensorLayout;
import com.smartinsole.device.SensorLayoutRepository;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.MeasurementQualityRepository;
import com.smartinsole.measurement.MeasurementSession;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import com.smartinsole.support.TestSessions;
import com.smartinsole.support.TestAnalysisProperties;

class RealtimeSnapshotStoreTest {
    @Test
    void keepsLastFootDataButMarksItDisconnectedAfterTimeout() {
        Instant receivedAt = Instant.parse("2026-09-02T07:00:00Z");
        UUID leftDevice = UUID.randomUUID();
        UUID rightDevice = UUID.randomUUID();
        CalibrationProfile leftCalibration = CalibrationProfile.identity(leftDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", receivedAt);
        CalibrationProfile rightCalibration = CalibrationProfile.identity(rightDevice,
                "[0,0,0,0,0,0,0,0]", "[1,1,1,1,1,1,1,1]", receivedAt);
        MeasurementSession session = TestSessions.create(UUID.randomUUID(), leftDevice, rightDevice,
                leftCalibration.getId(), rightCalibration.getId(), "layout-v1", "layout-v1", 100, null,
                receivedAt.minusSeconds(1));
        session.start(receivedAt.minusSeconds(1));
        SensorLayout layout = new SensorLayout("layout-v1", 8, points(), true, receivedAt);

        CalibrationProfileRepository calibrations = mock(CalibrationProfileRepository.class);
        SensorLayoutRepository layouts = mock(SensorLayoutRepository.class);
        MeasurementQualityRepository qualities = mock(MeasurementQualityRepository.class);
        when(calibrations.findById(leftCalibration.getId())).thenReturn(Optional.of(leftCalibration));
        when(calibrations.findById(rightCalibration.getId())).thenReturn(Optional.of(rightCalibration));
        when(layouts.findById("layout-v1")).thenReturn(Optional.of(layout));
        when(qualities.findById(session.getId())).thenReturn(Optional.empty());
        RealtimeSnapshotStore store = new RealtimeSnapshotStore(calibrations, layouts, qualities,
                new ObjectMapper(), new RealtimeProperties(10, Duration.ofSeconds(2)),
                TestAnalysisProperties.defaults());
        List<Integer> raw = List.of(0, 0, 0, 0, 0, 0, 0, 0);
        store.update(session, List.of(new PressureFrameData(leftDevice, FootSide.LEFT, 1, 10, raw)), receivedAt);

        RealtimeDtos.RealtimePressureMessage message = store.message(session, receivedAt.plusSeconds(3));

        assertThat(message.left()).isNotNull();
        assertThat(message.left().connected()).isFalse();
        assertThat(message.left().cop()).isNull();
        assertThat(message.right()).isNull();
        assertThat(message.quality().flags()).contains("LEFT_DEVICE_DISCONNECTED");
        assertThat(message.quality().flags()).contains("RIGHT_DATA_MISSING", "RIGHT_DEVICE_DISCONNECTED");
        assertThat(message.quality().score()).isLessThan(100);
        assertThat(raw).containsOnly(0);
    }

    private static String points() {
        return """
                [{"index":0,"x":0.5,"y":0.9,"region":"HEEL","medialLateral":"CENTER"},
                 {"index":1,"x":0.4,"y":0.7,"region":"MIDFOOT","medialLateral":"MEDIAL"},
                 {"index":2,"x":0.6,"y":0.7,"region":"MIDFOOT","medialLateral":"LATERAL"},
                 {"index":3,"x":0.3,"y":0.4,"region":"FOREFOOT","medialLateral":"MEDIAL"},
                 {"index":4,"x":0.5,"y":0.4,"region":"FOREFOOT","medialLateral":"CENTER"},
                 {"index":5,"x":0.7,"y":0.4,"region":"FOREFOOT","medialLateral":"LATERAL"},
                 {"index":6,"x":0.4,"y":0.1,"region":"TOE","medialLateral":"MEDIAL"},
                 {"index":7,"x":0.6,"y":0.1,"region":"TOE","medialLateral":"LATERAL"}]
                """;
    }
}
