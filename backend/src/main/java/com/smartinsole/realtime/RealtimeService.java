package com.smartinsole.realtime;

import com.smartinsole.global.common.DomainTypes.MeasurementStatus;
import com.smartinsole.global.config.RealtimeProperties;
import com.smartinsole.global.error.BusinessException;
import com.smartinsole.global.error.ErrorCode;
import com.smartinsole.measurement.IngestionDtos.FramesPersistedEvent;
import com.smartinsole.measurement.MeasurementDtos.SessionClosedEvent;
import com.smartinsole.measurement.MeasurementSession;
import com.smartinsole.measurement.MeasurementSessionRepository;
import com.smartinsole.realtime.RealtimeDtos.RealtimePressureMessage;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Service
public class RealtimeService {
    private static final Logger log = LoggerFactory.getLogger(RealtimeService.class);
    private final MeasurementSessionRepository sessions;
    private final RealtimeSnapshotStore snapshots;
    private final SimpMessagingTemplate messaging;
    private final RealtimeProperties properties;
    private final Clock clock;
    private final Map<UUID, Instant> lastPublished = new ConcurrentHashMap<>();
    private final Map<UUID, ConnectionState> lastPublishedConnections = new ConcurrentHashMap<>();

    public RealtimeService(MeasurementSessionRepository sessions, RealtimeSnapshotStore snapshots,
                           SimpMessagingTemplate messaging, RealtimeProperties properties, Clock clock) {
        this.sessions = sessions;
        this.snapshots = snapshots;
        this.messaging = messaging;
        this.properties = properties;
        this.clock = clock;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public void framesPersisted(FramesPersistedEvent event) {
        MeasurementSession session = sessions.findById(event.sessionId()).orElse(null);
        if (session == null || session.getStatus() != MeasurementStatus.MEASURING) return;
        try {
            snapshots.update(session, event.frames(), event.receivedAt());
            Instant now = Instant.now(clock);
            long intervalNanos = 1_000_000_000L / properties.publishHz();
            AtomicBoolean shouldPublish = new AtomicBoolean();
            lastPublished.compute(session.getId(), (ignored, previous) -> {
                if (previous == null || java.time.Duration.between(previous, now).toNanos() >= intervalNanos) {
                    shouldPublish.set(true);
                    return now;
                }
                return previous;
            });
            if (shouldPublish.get()) {
                publish(session.getId(), snapshots.message(session, now));
            }
        } catch (RuntimeException exception) {
            log.warn("Realtime projection or publish failed for session {}", session.getId(), exception);
        }
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void sessionClosed(SessionClosedEvent event) {
        snapshots.clear(event.sessionId());
        lastPublished.remove(event.sessionId());
        lastPublishedConnections.remove(event.sessionId());
    }

    @Transactional(readOnly = true)
    public void publishConnectionTransitions() {
        Instant now = Instant.now(clock);
        List<MeasurementSession> activeSessions = sessions.findAllByStatus(MeasurementStatus.MEASURING);
        for (MeasurementSession session : activeSessions) {
            try {
                RealtimePressureMessage message = snapshots.message(session, now);
                ConnectionState current = ConnectionState.from(message);
                ConnectionState previous = lastPublishedConnections.get(session.getId());
                if (!current.equals(previous) && (previous != null || current.hasDisconnectedFoot())) {
                    publish(session.getId(), message);
                    lastPublished.put(session.getId(), now);
                }
            } catch (RuntimeException exception) {
                log.warn("Realtime disconnect projection or publish failed for session {}",
                        session.getId(), exception);
            }
        }
    }

    @Transactional(readOnly = true)
    public RealtimePressureMessage getSnapshot(UUID sessionId, UUID userId) {
        MeasurementSession session = sessions.findByIdAndUserId(sessionId, userId).orElseThrow(() ->
                sessions.existsById(sessionId)
                        ? new BusinessException(ErrorCode.ACCESS_DENIED)
                        : new BusinessException(ErrorCode.RESOURCE_NOT_FOUND));
        if (session.getStatus() != MeasurementStatus.MEASURING) {
            throw new BusinessException(ErrorCode.SESSION_NOT_MEASURING,
                    ErrorCode.SESSION_NOT_MEASURING.defaultMessage(),
                    Map.of("currentStatus", session.getStatus().name()));
        }
        return snapshots.message(session, Instant.now(clock));
    }

    public static String topic(UUID sessionId) {
        return "/topic/measurement-sessions/" + sessionId + "/pressure";
    }

    private void publish(UUID sessionId, RealtimePressureMessage message) {
        messaging.convertAndSend(topic(sessionId), message);
        lastPublishedConnections.put(sessionId, ConnectionState.from(message));
    }

    private record ConnectionState(Boolean leftConnected, Boolean rightConnected) {
        static ConnectionState from(RealtimePressureMessage message) {
            return new ConnectionState(message.left() == null ? null : message.left().connected(),
                    message.right() == null ? null : message.right().connected());
        }

        boolean hasDisconnectedFoot() {
            return Boolean.FALSE.equals(leftConnected) || Boolean.FALSE.equals(rightConnected);
        }
    }
}
