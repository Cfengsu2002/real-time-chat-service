package dev.relay.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.relay.chat.api.ApiException;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.auth.JwtService;
import dev.relay.chat.chat.RoomRepository;
import dev.relay.chat.realtime.ChatWebSocketHandler;
import dev.relay.chat.realtime.LocalTicketStore;
import dev.relay.chat.realtime.TicketHandshakeInterceptor;
import dev.relay.chat.realtime.TicketService;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Date;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RealtimeSecurityTest {
    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule());
    private final TicketService tickets = new TicketService(new LocalTicketStore(), json);
    private final ChatPrincipal principal = new ChatPrincipal(1L, "tester", Instant.now().plusSeconds(3600));

    @Test
    void ticketIsSingleUseAndCappedByTokenExpiry() {
        TicketService.TicketView ticket = tickets.issue(principal);
        assertThat(ticket.expiresIn()).isEqualTo(30);
        assertThat(tickets.consume(ticket.ticket())).isEqualTo(principal);
        assertThatThrownBy(() -> tickets.consume(ticket.ticket())).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> tickets.consume("invalid")).isInstanceOf(ApiException.class);
        TicketService.TicketView nearExpiry = tickets.issue(new ChatPrincipal(1L, "tester", Instant.now().plusSeconds(5)));
        assertThat(nearExpiry.expiresIn()).isBetween(1L, 5L);
        assertThatThrownBy(() -> tickets.issue(new ChatPrincipal(1L, "tester", Instant.now().minusSeconds(1))))
                .isInstanceOf(ApiException.class);
    }

    @Test
    void expiredTicketCannotBeRedeemed() throws Exception {
        TicketService.TicketView ticket = tickets.issue(new ChatPrincipal(1L, "tester", Instant.now().plusMillis(20)));
        Thread.sleep(40);
        assertThatThrownBy(() -> tickets.consume(ticket.ticket())).isInstanceOf(ApiException.class);
    }

    @Test
    void crossOriginHandshakeIsRejectedWithoutConsumingTheTicket() {
        TicketHandshakeInterceptor interceptor = new TicketHandshakeInterceptor(tickets);
        String ticket = tickets.issue(principal).ticket();
        MockHttpServletRequest foreign = request(ticket, "https://evil.example");
        MockHttpServletResponse rejected = new MockHttpServletResponse();
        assertThat(interceptor.beforeHandshake(new ServletServerHttpRequest(foreign), new ServletServerHttpResponse(rejected), null, new HashMap<>())).isFalse();
        assertThat(rejected.getStatus()).isEqualTo(HttpStatus.FORBIDDEN.value());
        Map<String, Object> attributes = new HashMap<>();
        assertThat(interceptor.beforeHandshake(new ServletServerHttpRequest(request(ticket, "http://localhost:8080")),
                new ServletServerHttpResponse(new MockHttpServletResponse()), null, attributes)).isTrue();
        assertThat(attributes.get(TicketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE)).isEqualTo(principal);
        MockHttpServletResponse reused = new MockHttpServletResponse();
        assertThat(interceptor.beforeHandshake(new ServletServerHttpRequest(request(ticket, "http://localhost:8080")),
                new ServletServerHttpResponse(reused), null, new HashMap<>())).isFalse();
        assertThat(reused.getStatus()).isEqualTo(401);
    }

    @Test
    void socketIsClosedWhenAuthenticationExpires() throws Exception {
        ChatWebSocketHandler handler = new ChatWebSocketHandler(json, mock(RoomRepository.class));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("expired");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(TicketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new ChatPrincipal(1L, "tester", Instant.now().minusSeconds(1))));
        handler.afterConnectionEstablished(session);
        verify(session).close(CloseStatus.POLICY_VIOLATION);
        verify(session, never()).sendMessage(any(TextMessage.class));
    }

    @Test
    void scheduledExpiryClosesAnAlreadyEstablishedConnection() throws Exception {
        ChatWebSocketHandler handler = new ChatWebSocketHandler(json, mock(RoomRepository.class));
        WebSocketSession session = mock(WebSocketSession.class);
        when(session.getId()).thenReturn("short-lived");
        when(session.isOpen()).thenReturn(true);
        when(session.getAttributes()).thenReturn(Map.of(TicketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new ChatPrincipal(1L, "tester", Instant.now().plusMillis(100))));
        handler.afterConnectionEstablished(session);
        verify(session).sendMessage(any(TextMessage.class));
        Thread.sleep(150);
        handler.closeExpiredSessions();
        verify(session).close(CloseStatus.POLICY_VIOLATION);
    }

    @Test
    void signedButExpiredJwtIsRejected() throws Exception {
        String secret = "this-is-a-test-key-with-at-least-32-bytes";
        JwtService jwt = new JwtService(secret, 3600);
        SignedJWT expired = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer("relay-chat").subject("1").claim("username", "tester")
                .expirationTime(Date.from(Instant.now().minusSeconds(1))).build());
        expired.sign(new MACSigner(secret));
        assertThatThrownBy(() -> jwt.verify(expired.serialize())).isInstanceOf(ApiException.class);
    }

    @Test
    void weakSecretsAndInvalidJwtAreRejected() {
        assertThatThrownBy(() -> new JwtService("too-short", 3600)).isInstanceOf(IllegalArgumentException.class);
        JwtService jwt = new JwtService("this-is-a-test-key-with-at-least-32-bytes", 3600);
        assertThatThrownBy(() -> jwt.verify("malformed")).isInstanceOf(ApiException.class);
    }

    private MockHttpServletRequest request(String ticket, String origin) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws");
        request.setScheme("http");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setQueryString("ticket=" + ticket);
        request.addHeader("Origin", origin);
        return request;
    }
}
