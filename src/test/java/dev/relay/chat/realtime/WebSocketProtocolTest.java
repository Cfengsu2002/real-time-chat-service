package dev.relay.chat.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.message.MessageView;
import dev.relay.chat.room.RoomRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WebSocketProtocolTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");
    private ChatWebSocketHandler handler;
    private RoomRepository rooms;

    @BeforeEach
    void setUp() {
        rooms = mock(RoomRepository.class);
        when(rooms.existsById(1L)).thenReturn(true);
        when(rooms.existsById(2L)).thenReturn(true);
        handler = new ChatWebSocketHandler(json, rooms, Clock.fixed(now, ZoneOffset.UTC));
    }

    @AfterEach
    void close() {
        handler.shutdown();
    }

    @Test
    void onlySubscribersToTheMessageRoomReceiveItAndSwitchingReplacesSubscription() throws Exception {
        List<JsonNode> alice = new ArrayList<>();
        List<JsonNode> bob = new ArrayList<>();
        WebSocketSession aliceSession = session("alice", alice, now.plusSeconds(3600));
        WebSocketSession bobSession = session("bob", bob, now.plusSeconds(3600));
        handler.afterConnectionEstablished(aliceSession);
        handler.afterConnectionEstablished(bobSession);
        handler.handleMessage(aliceSession, new TextMessage("{\"type\":\"subscribe\",\"roomId\":1}"));
        handler.handleMessage(bobSession, new TextMessage("{\"type\":\"subscribe\",\"roomId\":2}"));
        handler.broadcast(message(1));
        assertThat(alice).extracting(node -> node.path("type").asText()).containsExactly("subscribed", "message");
        assertThat(bob).extracting(node -> node.path("type").asText()).containsExactly("subscribed");
        handler.handleMessage(aliceSession, new TextMessage("{\"type\":\"subscribe\",\"roomId\":2}"));
        handler.broadcast(message(1));
        handler.broadcast(message(2));
        assertThat(alice).extracting(node -> node.path("type").asText())
                .containsExactly("subscribed", "message", "subscribed", "message");
        assertThat(bob).extracting(node -> node.path("type").asText()).containsExactly("subscribed", "message");
        assertThat(bob.get(1).path("message").path("content").asText()).isEqualTo("<script>literal text</script>");
    }

    @Test
    void malformedFramesUnknownFieldsMissingRoomsAndPingUseExplicitProtocolResponses() throws Exception {
        List<JsonNode> output = new ArrayList<>();
        WebSocketSession socket = session("alex", output, now.plusSeconds(3600));
        handler.afterConnectionEstablished(socket);
        for (String invalid : List.of("{", "[]", "null", "{}", "{\"type\":\"send\"}",
                "{\"type\":\"subscribe\",\"roomId\":1.5}", "{\"type\":\"subscribe\",\"roomId\":1,\"extra\":true}",
                "{\"type\":\"ping\",\"extra\":true}")) {
            handler.handleMessage(socket, new TextMessage(invalid));
            assertThat(output.get(output.size() - 1).path("code").asText()).isEqualTo("invalid_frame");
        }
        handler.handleMessage(socket, new TextMessage("{\"type\":\"subscribe\",\"roomId\":999}"));
        assertThat(output.get(output.size() - 1).path("code").asText()).isEqualTo("room_not_found");
        handler.handleMessage(socket, new TextMessage("{\"type\":\"ping\"}"));
        assertThat(output.get(output.size() - 1).path("type").asText()).isEqualTo("pong");
    }

    @Test
    void closedConnectionsAreRemovedAndExpiredPrincipalCannotConnect() throws Exception {
        List<JsonNode> output = new ArrayList<>();
        WebSocketSession socket = session("alex", output, now.plusSeconds(3600));
        handler.afterConnectionEstablished(socket);
        handler.handleMessage(socket, new TextMessage("{\"type\":\"subscribe\",\"roomId\":1}"));
        handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
        assertThat(expiryQueueSize()).isZero();
        handler.broadcast(message(1));
        assertThat(output).hasSize(1);
        WebSocketSession expired = session("expired", new ArrayList<>(), now);
        handler.afterConnectionEstablished(expired);
        verify(expired).close(new CloseStatus(4401, "Sign in again"));
    }

    @Test
    void closeDuringEstablishmentDoesNotRetainAnExpiryTaskForTheClosedSession() throws Exception {
        handler.shutdown();
        Clock clock = mock(Clock.class);
        WebSocketSession socket = session("racing-close", new ArrayList<>(), now.plusSeconds(86400));
        AtomicInteger reads = new AtomicInteger();
        when(clock.instant()).thenAnswer(call -> {
            if (reads.incrementAndGet() == 2) {
                // 第二次读取时间发生在会话登记后、过期任务赋值前。
                handler.afterConnectionClosed(socket, CloseStatus.NORMAL);
            }
            return now;
        });
        handler = new ChatWebSocketHandler(json, rooms, clock);
        handler.afterConnectionEstablished(socket);
        assertThat(reads.get()).isEqualTo(2);
        assertThat(expiryQueueSize()).isZero();
    }

    private int expiryQueueSize() {
        ScheduledThreadPoolExecutor scheduler = (ScheduledThreadPoolExecutor) ReflectionTestUtils.getField(handler, "expirations");
        return scheduler.getQueue().size();
    }

    private WebSocketSession session(String id, List<JsonNode> output, Instant expiresAt) throws Exception {
        WebSocketSession socket = mock(WebSocketSession.class);
        when(socket.getId()).thenReturn(id);
        when(socket.isOpen()).thenReturn(true);
        when(socket.getAttributes()).thenReturn(Map.of(TicketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE,
                new ChatPrincipal(1, "alex", expiresAt)));
        doAnswer(call -> {
            WebSocketMessage<?> frame = call.getArgument(0);
            output.add(json.readTree(frame.getPayload().toString()));
            return null;
        }).when(socket).sendMessage(any());
        return socket;
    }

    private MessageView message(long roomId) {
        return new MessageView(roomId, roomId, 1, "alex", UUID.randomUUID(), "<script>literal text</script>", now);
    }
}
