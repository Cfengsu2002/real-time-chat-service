package dev.relay.chat.realtime;

import dev.relay.chat.auth.ChatPrincipal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.http.server.ServletServerHttpResponse;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.socket.WebSocketHandler;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class TicketAuthorizationTest {
    private MutableClock clock;
    private TicketService tickets;
    private TicketHandshakeInterceptor handshake;
    private ChatPrincipal principal;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(Instant.parse("2026-10-24T12:00:00Z"));
        tickets = new TicketService(new LocalTicketStore(clock), clock);
        handshake = new TicketHandshakeInterceptor(new OriginPolicy("http://localhost:8080,https://chat.example.com"), tickets);
        principal = new ChatPrincipal(1, "alex", clock.instant().plusSeconds(3600));
    }

    @Test
    void issuedTicketIsOpaqueShortLivedAndConsumableExactlyOnce() {
        TicketService.TicketView issued = tickets.issue(principal);
        assertThat(issued.ticket()).matches("[A-Za-z0-9_-]{43}");
        assertThat(issued.expiresAt()).isEqualTo(clock.instant().plusSeconds(30));
        assertThat(tickets.consume(issued.ticket())).contains(principal);
        assertThat(tickets.consume(issued.ticket())).isEmpty();
    }

    @Test
    void concurrentConsumersCannotReuseATicket() throws Exception {
        String ticket = tickets.issue(principal).ticket();
        var pool = Executors.newFixedThreadPool(8);
        try {
            List<Callable<Boolean>> attempts = new ArrayList<>();
            for (int index = 0; index < 32; index++) {
                attempts.add(() -> tickets.consume(ticket).isPresent());
            }
            long accepted = pool.invokeAll(attempts).stream().filter(result -> {
                try {
                    return result.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).count();
            assertThat(accepted).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void expirationRejectsTicketAtBoundaryAndIsBoundedByJwt() {
        TicketService.TicketView issued = tickets.issue(principal);
        clock.now = issued.expiresAt();
        assertThat(tickets.consume(issued.ticket())).isEmpty();
        ChatPrincipal nearlyExpired = new ChatPrincipal(1, "alex", clock.instant().plusSeconds(5));
        TicketService.TicketView shortTicket = tickets.issue(nearlyExpired);
        assertThat(shortTicket.expiresAt()).isEqualTo(nearlyExpired.expiresAt());
        clock.now = nearlyExpired.expiresAt();
        assertThat(tickets.consume(shortTicket.ticket())).isEmpty();
        assertThatThrownBy(() -> tickets.issue(nearlyExpired)).isInstanceOf(dev.relay.chat.api.ApiException.class);
    }

    @Test
    void missingOrUntrustedOriginIsRejectedWithoutConsumingTicket() {
        String ticket = tickets.issue(principal).ticket();
        for (String origin : new String[] { null, "null", "http://localhost:8080.evil.example", "https://evil.example",
                "http://localhost:8080/", "http://localhost:8080/path", "http://localhost:8080?x=1" }) {
            MockHttpServletResponse response = new MockHttpServletResponse();
            assertThat(attempt(ticket, origin, response, new HashMap<>())).isFalse();
            assertThat(response.getStatus()).isEqualTo(403);
        }
        assertThat(tickets.consume(ticket)).contains(principal);
    }

    @Test
    void allowedHandshakeAssignsPrincipalAndReplayIsRejected() {
        String ticket = tickets.issue(principal).ticket();
        var attributes = new HashMap<String, Object>();
        assertThat(attempt(ticket, "http://localhost:8080", new MockHttpServletResponse(), attributes)).isTrue();
        assertThat(attributes).containsEntry(TicketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE, principal);
        MockHttpServletResponse replay = new MockHttpServletResponse();
        assertThat(attempt(ticket, "http://localhost:8080", replay, new HashMap<>())).isFalse();
        assertThat(replay.getStatus()).isEqualTo(401);
    }

    @Test
    void duplicateTicketParametersAndMalformedTicketsAreRejected() {
        String ticket = tickets.issue(principal).ticket();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setQueryString("ticket=" + ticket + "&ticket=" + ticket);
        request.addHeader("Origin", "http://localhost:8080");
        MockHttpServletResponse response = new MockHttpServletResponse();
        assertThat(handshake.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class), new HashMap<>())).isFalse();
        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(tickets.consume(ticket)).contains(principal);
        assertThat(tickets.consume("not-a-ticket")).isEmpty();
    }

    @Test
    void explicitOriginsRejectWildcardsCredentialsAndPaths() {
        for (String invalid : List.of("*", "https://*.example.com", "https://alex:secret@example.com", "https://example.com/path", "")) {
            assertThatThrownBy(() -> new OriginPolicy(invalid)).isInstanceOf(IllegalArgumentException.class);
        }
        OriginPolicy policy = new OriginPolicy("https://chat.example.com");
        assertThat(policy.allows("https://chat.example.com:443")).isTrue();
        assertThat(policy.allows("http://chat.example.com")).isFalse();
    }

    private boolean attempt(String ticket, String origin, MockHttpServletResponse response, HashMap<String, Object> attributes) {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/ws");
        request.setServerName("localhost");
        request.setServerPort(8080);
        request.setQueryString("ticket=" + ticket);
        if (origin != null) {
            request.addHeader("Origin", origin);
        }
        return handshake.beforeHandshake(new ServletServerHttpRequest(request), new ServletServerHttpResponse(response),
                mock(WebSocketHandler.class), attributes);
    }

    private static final class MutableClock extends Clock {
        private Instant now;

        private MutableClock(Instant now) {
            this.now = now;
        }

        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
}
