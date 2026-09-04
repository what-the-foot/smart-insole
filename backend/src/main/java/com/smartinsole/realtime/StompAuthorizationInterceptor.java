package com.smartinsole.realtime;

import com.smartinsole.global.security.AuthenticatedUser;
import com.smartinsole.global.security.JwtService;
import com.smartinsole.measurement.MeasurementSessionRepository;
import java.time.Clock;
import java.time.Instant;
import java.util.Map;
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
import org.springframework.messaging.support.MessageHeaderAccessor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;

@Component
public class StompAuthorizationInterceptor implements ChannelInterceptor {
    private static final Pattern SESSION_TOPIC = Pattern.compile(
            "^/topic/measurement-sessions/([0-9a-fA-F-]{36})/pressure$");
    private final JwtService jwtService;
    private final MeasurementSessionRepository sessions;
    private final Clock clock;
    private final Map<String, Instant> sessionExpirations = new ConcurrentHashMap<>();

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
            try {
                AuthenticatedUser user = jwtService.parse(header.substring(7));
                if (user.isExpired(Instant.now(clock))) {
                    throw new AccessDeniedException("Expired STOMP bearer token");
                }
                accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
                if (accessor.getSessionId() != null) {
                    sessionExpirations.put(accessor.getSessionId(), user.expiresAt());
                }
            } catch (RuntimeException exception) {
                throw new AccessDeniedException("Invalid STOMP bearer token", exception);
            }
        } else if (accessor.getCommand() == StompCommand.SEND) {
            // Realtime topics are server-published projections. Clients must never be able to
            // impersonate the server or inject pressure data into the simple broker.
            throw new AccessDeniedException("Client STOMP SEND frames are not allowed");
        } else if (accessor.getCommand() == StompCommand.SUBSCRIBE) {
            AuthenticatedUser user = authenticatedUser(accessor);
            if (user.isExpired(Instant.now(clock))) {
                throw new AccessDeniedException("STOMP bearer token has expired");
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
        }
        return message;
    }

    public ChannelInterceptor outboundInterceptor() {
        return new ChannelInterceptor() {
            @Override
            public Message<?> preSend(Message<?> message, MessageChannel channel) {
                if (SimpMessageHeaderAccessor.getMessageType(message.getHeaders()) != SimpMessageType.MESSAGE) {
                    return message;
                }
                StompHeaderAccessor accessor = StompHeaderAccessor.wrap(message);
                return outboundTokenExpired(accessor) ? null : message;
            }
        };
    }

    private boolean outboundTokenExpired(StompHeaderAccessor accessor) {
        Instant now = Instant.now(clock);
        String sessionId = accessor.getSessionId();
        Instant expiration = sessionId == null ? null : sessionExpirations.get(sessionId);
        if (expiration == null && accessor.getUser() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            expiration = user.expiresAt();
        }
        if (expiration != null && !expiration.isAfter(now)) {
            if (sessionId != null) sessionExpirations.remove(sessionId);
            return true;
        }
        return sessionId != null && expiration == null;
    }

    private static AuthenticatedUser authenticatedUser(StompHeaderAccessor accessor) {
        if (accessor.getUser() instanceof Authentication authentication
                && authentication.getPrincipal() instanceof AuthenticatedUser user) {
            return user;
        }
        throw new AccessDeniedException("STOMP connection is not authenticated");
    }
}
