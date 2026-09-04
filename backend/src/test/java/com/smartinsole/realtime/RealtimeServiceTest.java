package com.smartinsole.realtime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.smartinsole.global.common.DomainTypes.ContactState;
import com.smartinsole.global.common.DomainTypes.FootSide;
import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.common.DomainTypes.QualityLevel;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.measurement.IngestionDtos.FramesPersistedEvent;
import com.smartinsole.measurement.IngestionDtos.PressureFrameData;
import com.smartinsole.measurement.MeasurementSession;
import com.smartinsole.measurement.MeasurementSessionRepository;
import com.smartinsole.realtime.RealtimeDtos.FootRealtimeData;
import com.smartinsole.realtime.RealtimeDtos.RealtimePressureMessage;
import com.smartinsole.realtime.RealtimeDtos.RealtimeQuality;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.messaging.simp.SimpMessagingTemplate;

class RealtimeServiceTest {
    @Test
    void publishesOneDisconnectedTransitionWhenBothFeetStopSendingFrames() {
        Instant receivedAt = Instant.parse("2026-09-02T07:00:00Z");
        Instant timedOutAt = receivedAt.plusSeconds(3);
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(receivedAt, timedOutAt, timedOutAt);
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        MeasurementSession session = measuringSession(receivedAt);
        MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
        RealtimeSnapshotStore snapshots = mock(RealtimeSnapshotStore.class);
        SimpMessagingTemplate messaging = mock(SimpMessagingTemplate.class);
        when(sessions.findById(session.getId())).thenReturn(java.util.Optional.of(session));
        when(sessions.findAllByStatus(MeasurementStatus.MEASURING)).thenReturn(List.of(session));
        when(snapshots.message(eq(session), any(Instant.class))).thenAnswer(invocation -> {
            Instant now = invocation.getArgument(1);
            return message(session.getId(), now, now.equals(receivedAt));
        });
        RealtimeService service = new RealtimeService(sessions, snapshots, messaging,
                new RealtimeProperties(10, Duration.ofSeconds(2)), clock);
        PressureFrameData frame = new PressureFrameData(session.getLeftDeviceId(), FootSide.LEFT,
                1, 10, List.of(1, 1, 1, 1, 1, 1, 1, 1));

        service.framesPersisted(new FramesPersistedEvent(session.getId(), List.of(frame), receivedAt));
        service.publishConnectionTransitions();
        service.publishConnectionTransitions();

        ArgumentCaptor<RealtimePressureMessage> messages = ArgumentCaptor.forClass(RealtimePressureMessage.class);
        verify(messaging, times(2)).convertAndSend(eq(RealtimeService.topic(session.getId())), messages.capture());
        RealtimePressureMessage disconnected = messages.getAllValues().get(1);
        assertThat(disconnected.left().connected()).isFalse();
        assertThat(disconnected.right().connected()).isFalse();
        verify(sessions, times(2)).findAllByStatus(MeasurementStatus.MEASURING);
    }

    private static MeasurementSession measuringSession(Instant now) {
        MeasurementSession session = MeasurementSession.create(UUID.randomUUID(), UUID.randomUUID(),
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "layout-v1", "layout-v1",
                100, null, now.minusSeconds(1));
        session.start(now.minusSeconds(1));
        return session;
    }

    private static RealtimePressureMessage message(UUID sessionId, Instant now, boolean connected) {
        FootRealtimeData left = foot(connected, now);
        FootRealtimeData right = foot(connected, now);
        return new RealtimePressureMessage("1.0", sessionId, now, 1000, "MEASURING", left, right,
                new RealtimeQuality(connected ? 100 : 70,
                        connected ? QualityLevel.GOOD : QualityLevel.ACCEPTABLE,
                        connected ? List.of() : List.of("LEFT_DEVICE_DISCONNECTED",
                                "RIGHT_DEVICE_DISCONNECTED")));
    }

    private static FootRealtimeData foot(boolean connected, Instant now) {
        return new FootRealtimeData(connected, 1, 10, List.of(1.0, 1.0), 2,
                null, ContactState.NO_CONTACT, connected ? now : now.minusSeconds(3));
    }
}
