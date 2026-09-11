package com.smartinsole.realtime.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.smartinsole.global.security.AuthenticatedUser;
import com.smartinsole.global.security.JwtService;
import com.smartinsole.measurement.repository.MeasurementSessionRepository;
import com.smartinsole.realtime.service.RealtimeService;
import java.util.Optional;
import java.util.UUID;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.stomp.StompCommand;
import org.springframework.messaging.simp.stomp.StompHeaderAccessor;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;

class StompAuthorizationInterceptorTest {
    @Test
    void rejectsEveryClientSendFrame() {
        StompAuthorizationInterceptor interceptor = new StompAuthorizationInterceptor(
                mock(JwtService.class), mock(MeasurementSessionRepository.class), Clock.systemUTC());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SEND);
        accessor.setDestination(RealtimeService.topic(UUID.randomUUID()));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class)))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("SEND");
    }

    @Test
    void rejectsSubscriptionToAnotherUsersSession() {
        MeasurementSessionRepository sessions = mock(MeasurementSessionRepository.class);
        JwtService jwtService = mock(JwtService.class);
        StompAuthorizationInterceptor interceptor = new StompAuthorizationInterceptor(
                jwtService, sessions, Clock.systemUTC());
        UUID sessionId = UUID.randomUUID();
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "user@example.com");
        when(sessions.findByIdAndUserId(sessionId, user.userId())).thenReturn(Optional.empty());
        StompHeaderAccessor accessor = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        accessor.setDestination(RealtimeService.topic(sessionId));
        accessor.setUser(new UsernamePasswordAuthenticationToken(user, null, java.util.List.of()));
        Message<byte[]> message = MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());

        assertThatThrownBy(() -> interceptor.preSend(message, mock(org.springframework.messaging.MessageChannel.class)))
                .isInstanceOf(AccessDeniedException.class);
    }

    @Test
    void sendsOneTokenExpiredErrorFrameAndThenDropsOutboundMessagesAfterTheConnectTokenExpires() {
        Instant now = Instant.parse("2026-09-02T07:00:00Z");
        Clock clock = mock(Clock.class);
        when(clock.instant()).thenReturn(now, now.plusSeconds(2), now.plusSeconds(3));
        when(clock.getZone()).thenReturn(ZoneOffset.UTC);
        JwtService jwtService = mock(JwtService.class);
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "user@example.com",
                now.plusSeconds(1));
        when(jwtService.parse("token")).thenReturn(user);
        StompAuthorizationInterceptor interceptor = new StompAuthorizationInterceptor(jwtService,
                mock(MeasurementSessionRepository.class), clock);
        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId("session-1");
        connect.setNativeHeader("Authorization", "Bearer token");
        connect.setLeaveMutable(true);
        Message<byte[]> connectMessage = MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
        interceptor.preSend(connectMessage, mock(org.springframework.messaging.MessageChannel.class));
        MessageChannel channel = mock(MessageChannel.class);

        Message<?> replaced = interceptor.outboundInterceptor().preSend(brokerMessage("session-1"), channel);

        assertThat(replaced).isNotNull();
        StompHeaderAccessor error = StompHeaderAccessor.wrap(replaced);
        assertThat(error.getCommand()).isEqualTo(StompCommand.ERROR);
        assertThat(error.getMessage()).isEqualTo(StompAuthorizationInterceptor.TOKEN_EXPIRED);
        assertThat(error.getSessionId()).isEqualTo("session-1");
        assertThat(new String((byte[]) replaced.getPayload(), java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("TOKEN_EXPIRED");
        // The ERROR frame closes the socket; nothing else is relayed to that session afterwards.
        assertThat(interceptor.outboundInterceptor().preSend(brokerMessage("session-1"), channel)).isNull();
    }

    @Test
    void rejectsConnectAndSubscribeWithAnExpiredTokenUsingTheTokenExpiredMessage() {
        Instant now = Instant.parse("2026-09-02T07:00:00Z");
        JwtService jwtService = mock(JwtService.class);
        AuthenticatedUser expired = new AuthenticatedUser(UUID.randomUUID(), "user@example.com",
                now.minusSeconds(1));
        when(jwtService.parse("token")).thenReturn(expired);
        StompAuthorizationInterceptor interceptor = new StompAuthorizationInterceptor(jwtService,
                mock(MeasurementSessionRepository.class), Clock.fixed(now, ZoneOffset.UTC));
        MessageChannel channel = mock(MessageChannel.class);

        assertThatThrownBy(() -> interceptor.preSend(connect("session-1"), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage(StompAuthorizationInterceptor.TOKEN_EXPIRED);

        StompHeaderAccessor subscribe = StompHeaderAccessor.create(StompCommand.SUBSCRIBE);
        subscribe.setDestination(RealtimeService.topic(UUID.randomUUID()));
        subscribe.setUser(new UsernamePasswordAuthenticationToken(expired, null, java.util.List.of()));
        assertThatThrownBy(() -> interceptor.preSend(
                MessageBuilder.createMessage(new byte[0], subscribe.getMessageHeaders()), channel))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage(StompAuthorizationInterceptor.TOKEN_EXPIRED);
    }

    @Test
    void passesConnectAckAndKeepsIndependentSessionExpiryState() {
        Instant now = Instant.parse("2026-09-02T07:00:00Z");
        JwtService jwtService = mock(JwtService.class);
        AuthenticatedUser user = new AuthenticatedUser(UUID.randomUUID(), "user@example.com",
                now.plusSeconds(60));
        when(jwtService.parse("token")).thenReturn(user);
        StompAuthorizationInterceptor interceptor = new StompAuthorizationInterceptor(jwtService,
                mock(MeasurementSessionRepository.class), Clock.fixed(now, ZoneOffset.UTC));
        MessageChannel channel = mock(MessageChannel.class);

        interceptor.preSend(connect("session-1"), channel);
        interceptor.preSend(connect("session-2"), channel);

        SimpMessageHeaderAccessor ack = SimpMessageHeaderAccessor.create(SimpMessageType.CONNECT_ACK);
        ack.setSessionId("session-1");
        Message<byte[]> ackMessage = MessageBuilder.createMessage(new byte[0], ack.getMessageHeaders());
        assertThat(interceptor.outboundInterceptor().preSend(ackMessage, channel)).isSameAs(ackMessage);

        StompHeaderAccessor disconnect = StompHeaderAccessor.create(StompCommand.DISCONNECT);
        disconnect.setSessionId("session-1");
        interceptor.preSend(MessageBuilder.createMessage(new byte[0], disconnect.getMessageHeaders()), channel);

        org.assertj.core.api.Assertions.assertThat(interceptor.outboundInterceptor().preSend(
                brokerMessage("session-1"), channel)).isNull();
        org.assertj.core.api.Assertions.assertThat(interceptor.outboundInterceptor().preSend(
                brokerMessage("session-2"), channel)).isNotNull();
    }

    private static Message<byte[]> connect(String sessionId) {
        StompHeaderAccessor connect = StompHeaderAccessor.create(StompCommand.CONNECT);
        connect.setSessionId(sessionId);
        connect.setNativeHeader("Authorization", "Bearer token");
        connect.setLeaveMutable(true);
        return MessageBuilder.createMessage(new byte[0], connect.getMessageHeaders());
    }

    private static Message<byte[]> brokerMessage(String sessionId) {
        SimpMessageHeaderAccessor message = SimpMessageHeaderAccessor.create(SimpMessageType.MESSAGE);
        message.setSessionId(sessionId);
        return MessageBuilder.createMessage(new byte[0], message.getMessageHeaders());
    }
}
