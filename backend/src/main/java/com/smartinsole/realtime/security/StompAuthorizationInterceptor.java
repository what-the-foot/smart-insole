package com.smartinsole.realtime.security;

import com.smartinsole.global.security.AuthenticatedUser;
import com.smartinsole.global.security.JwtService;
import com.smartinsole.measurement.repository.MeasurementSessionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.ChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

@Component
public class StompAuthorizationInterceptor implements ChannelInterceptor {
    /**
     * ERROR frame message sent when the CONNECT token expires mid-subscription. The frontend maps it to
     * AUTH_EXPIRED and re-subscribes after re-authentication.
     */
    public static final String TOKEN_EXPIRED = "TOKEN_EXPIRED";
    private static final Pattern SESSION_TOPIC = Pattern.compile(
            "^/topic/measurement-sessions/([0-9a-fA-F-]{36})/pressure$");
    private final JwtService jwtService;
    private final MeasurementSessionRepository sessions;
    private final Clock clock;
    private final Map<String, Instant> sessionExpirations = new ConcurrentHashMap<>();
    /** Sessions that already received the TOKEN_EXPIRED frame; later broker messages are dropped. */
    private final Set<String> terminatedSessions = ConcurrentHashMap.newKeySet();

    public StompAuthorizationInterceptor(JwtService jwtService, MeasurementSessionRepository sessions,
                                         Clock clock) {
        this.jwtService = jwtService;
        this.sessions = sessions;
        this.clock = clock;
    }

    @Override
    public Message<?> preSend(Message<?> message, MessageChannel channel) {
        StompHeaderAccessor accessor = MessageHeaderAccessor.getAccessor(message, StompHeaderAccessor.class);
        if (accessor == null) accessor = StompHeaderAccessor.wrap(message);
        if (accessor.getCommand() == StompCommand.CONNECT) {
            String header = accessor.getFirstNativeHeader("Authorization");
            if (header == null || !header.startsWith("Bearer ")) {
                throw new AccessDeniedException("Missing STOMP bearer token");
            }
            AuthenticatedUser user;
            try {
                user = jwtService.parse(header.substring(7));
            } catch (RuntimeException exception) {
                throw new AccessDeniedException("Invalid STOMP bearer token", exception);
            }
            if (user.isExpired(Instant.now(clock))) {
                throw new AccessDeniedException(TOKEN_EXPIRED);
            }
            accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
            if (accessor.getSessionId() != null) {
                sessionExpirations.put(accessor.getSessionId(), user.expiresAt());
                terminatedSessions.remove(accessor.getSessionId());
            }
        } else if (accessor.getCommand() == StompCommand.SEND) {
            // Realtime topics are server-published projections. Clients must never be able to
            // impersonate the server or inject pressure data into the simple broker.
            throw new AccessDeniedException("Client STOMP SEND frames are not allowed");
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            AuthenticatedUser user = authenticatedUser(accessor);
            if (user.isExpired(Instant.now(clock))) {
                throw new AccessDeniedException(TOKEN_EXPIRED);
            }
            Matcher matcher = SESSION_TOPIC.matcher(String.valueOf(accessor.getDestination()));
            if (!matcher.matches()) {
                throw new AccessDeniedException("Subscription destination is not allowed");
            }
            UUID sessionId;
            try {
                sessionId = UUID.fromString(matcher.group(1));
            } catch (IllegalArgumentException exception) {
                throw new AccessDeniedException("Invalid session topic", exception);
            }
            if (!sessions.findByIdAndUserId(sessionId, user.userId()).isPresent()) {
                throw new AccessDeniedException("Session subscription is not allowed");
            }
        } else if (accessor.getCommand() == StompCommand.DISCONNECT && accessor.getSessionId() != null) {
            sessionExpirations.remove(accessor.getSessionId());
            terminatedSessions.remove(accessor.getSessionId());
        }
        return message;
    }

    /**
     * Outbound guard: once the CONNECT token has expired the client receives one STOMP ERROR frame
     * ({@code message:TOKEN_EXPIRED}) in place of the next broker message. Spring's STOMP handler closes
     * the WebSocket after sending an ERROR frame, and any further broker messages for that session are
     * dropped here.
     */
    public ChannelInterceptor outboundInterceptor() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
                    return message;
                }
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                String sessionId = accessor.getSessionId();
                if (sessionId != null && terminatedSessions.contains(sessionId)) {
                    return null;
                }
                OutboundDecision decision = outboundDecision(accessor);
                return switch (decision) {
                    case DELIVER -> message;
                    case DROP -> null;
                    case EXPIRED -> tokenExpiredFrame(sessionId);
                };
            }
        };
    }

    private enum OutboundDecision { DELIVER, DROP, EXPIRED }

    private OutboundDecision outboundDecision(StompHeaderAccessor accessor) {
        Instant now = Instant.now(clock);
        String sessionId = accessor.getSessionId();
        Instant expiration = sessionId == null ? null : sessionExpirations.get(sessionId);
        if (expiration == null && accessor.getUser() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            expiration = user.expiresAt();
        }
        if (expiration != null && !expiration.isAfter(now)) {
            if (sessionId != null) {
                sessionExpirations.remove(sessionId);
                terminatedSessions.add(sessionId);
            }
            return OutboundDecision.EXPIRED;
        }
        // A session this interceptor never authenticated (or that already disconnected) gets nothing.
        return sessionId != null && expiration == null ? OutboundDecision.DROP : OutboundDecision.DELIVER;
    }

    static Message<byte[]> tokenExpiredFrame(String sessionId) {
        StompHeaderAccessor error = StompHeaderAccessor.create(StompCommand.ERROR);
        error.setMessage(TOKEN_EXPIRED);
        error.setContentType(MimeTypeUtils.TEXT_PLAIN);
        if (sessionId != null) {
            error.setSessionId(sessionId);
        }
        error.setLeaveMutable(true);
        return MessageBuilder.createMessage(TOKEN_EXPIRED.getBytes(StandardCharsets.UTF_8),
                error.getMessageHeaders());
    }

    private static AuthenticatedUser authenticatedUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new AccessDeniedException("STOMP connection is not authenticated");
    }
}
