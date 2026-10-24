package dev.relay.chat.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.auth.JwtService;
import dev.relay.chat.auth.UserAccount;
import dev.relay.chat.auth.UserRepository;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.net.http.WebSocketHandshakeException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:relay_realtime_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE",
        "app.realtime.allowed-origins=http://localhost:8080"
})
@ActiveProfiles("test")
class LiveConnectionTest {
    @LocalServerPort private int port;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private JwtService jwt;
    @Autowired private TicketService tickets;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    private UserAccount user;
    private String token;

    @BeforeEach
    void createUser() {
        user = users.saveAndFlush(new UserAccount("ws_" + UUID.randomUUID().toString().replace("-", "").substring(0, 15), "not-used"));
        token = jwt.issue(user).token();
    }

    @Test
    void ticketEndpointRequiresAuthenticationAndDisablesCaching() throws Exception {
        HttpResponse<String> unauthorized = post("/api/ws/tickets", null, "");
        assertThat(unauthorized.statusCode()).isEqualTo(401);
        HttpResponse<String> authorized = post("/api/ws/tickets", token, "");
        assertThat(authorized.statusCode()).isEqualTo(200);
        assertThat(authorized.headers().firstValue("Cache-Control")).contains("no-store");
        assertThat(json.readTree(authorized.body()).path("data").path("ticket").asText()).hasSize(43);
    }

    @Test
    void realSocketReceivesCommittedMessageAndRetryDoesNotBroadcastAgain() throws Exception {
        Incoming incoming = new Incoming();
        WebSocket socket = connect(ticket(), "http://localhost:8080", incoming);
        try {
            socket.sendText("{\"type\":\"subscribe\",\"roomId\":1}", true).join();
            assertThat(json.readTree(incoming.next()).path("type").asText()).isEqualTo("subscribed");
            String body = json.writeValueAsString(Map.of("clientMessageId", UUID.randomUUID(), "content", "Live over a real socket"));
            HttpResponse<String> first = post("/api/rooms/1/messages", token, body);
            assertThat(first.statusCode()).isEqualTo(201);
            JsonNode stored = json.readTree(first.body()).path("data");
            JsonNode delivered = json.readTree(incoming.next());
            assertThat(delivered.path("type").asText()).isEqualTo("message");
            assertThat(delivered.path("message").path("id").asLong()).isEqualTo(stored.path("id").asLong());
            assertThat(delivered.path("message").path("content").asText()).isEqualTo("Live over a real socket");
            HttpResponse<String> retried = post("/api/rooms/1/messages", token, body);
            assertThat(retried.statusCode()).isEqualTo(201);
            assertThat(json.readTree(retried.body()).path("data").path("id").asLong()).isEqualTo(stored.path("id").asLong());
            assertThat(incoming.frames.poll(300, TimeUnit.MILLISECONDS)).isNull();
            socket.sendText("{\"type\":\"ping\"}", true).join();
            assertThat(json.readTree(incoming.next()).path("type").asText()).isEqualTo("pong");
        } finally {
            socket.abort();
        }
    }

    @Test
    void hostileOriginCannotBurnTicketAndSocketReplayIsUnauthorized() throws Exception {
        String ticket = ticket();
        assertHandshakeRejected(ticket, "https://evil.example", 403);
        WebSocket socket = connect(ticket, "http://localhost:8080", new Incoming());
        try {
            assertHandshakeRejected(ticket, "http://localhost:8080", 401);
        } finally {
            socket.abort();
        }
    }

    @Test
    void realSocketClosesWhenAuthenticationExpires() throws Exception {
        String ticket = tickets.issue(new ChatPrincipal(user.getId(), user.getUsername(), Instant.now().plusMillis(1500))).ticket();
        Incoming incoming = new Incoming();
        WebSocket socket = connect(ticket, "http://localhost:8080", incoming);
        try {
            assertThat(incoming.closed.get(5, TimeUnit.SECONDS)).isEqualTo(4401);
        } finally {
            socket.abort();
        }
    }

    private String ticket() throws Exception {
        HttpResponse<String> response = post("/api/ws/tickets", token, "");
        assertThat(response.statusCode()).isEqualTo(200);
        return json.readTree(response.body()).path("data").path("ticket").asText();
    }

    private HttpResponse<String> post(String path, String authorization, String body) throws Exception {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + path))
                .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (authorization != null) {
            request.header("Authorization", "Bearer " + authorization);
        }
        return http.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private WebSocket connect(String ticket, String origin, Incoming incoming) {
        return http.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(3)).header("Origin", origin)
                .buildAsync(URI.create("ws://localhost:" + port + "/ws?ticket=" + ticket), incoming).join();
    }

    private void assertHandshakeRejected(String ticket, String origin, int status) {
        assertThatThrownBy(() -> connect(ticket, origin, new Incoming())).isInstanceOfSatisfying(CompletionException.class,
                failure -> assertThat(failure.getCause()).isInstanceOfSatisfying(WebSocketHandshakeException.class,
                        handshake -> assertThat(handshake.getResponse().statusCode()).isEqualTo(status)));
    }

    private static final class Incoming implements WebSocket.Listener {
        private final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        private final CompletableFuture<Integer> closed = new CompletableFuture<>();
        private final StringBuilder pending = new StringBuilder();

        @Override
        public void onOpen(WebSocket socket) {
            socket.request(1);
        }

        @Override
        public CompletionStage<?> onText(WebSocket socket, CharSequence text, boolean last) {
            pending.append(text);
            if (last) {
                frames.add(pending.toString());
                pending.setLength(0);
            }
            socket.request(1);
            return null;
        }

        @Override
        public CompletionStage<?> onClose(WebSocket socket, int code, String reason) {
            closed.complete(code);
            return null;
        }

        private String next() throws InterruptedException {
            String frame = frames.poll(5, TimeUnit.SECONDS);
            assertThat(frame).as("WebSocket frame received within five seconds").isNotNull();
            return frame;
        }
    }
}
