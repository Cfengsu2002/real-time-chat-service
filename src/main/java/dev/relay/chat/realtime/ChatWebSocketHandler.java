package dev.relay.chat.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.chat.MessageView;
import dev.relay.chat.chat.RoomRepository;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private final ConcurrentHashMap<String, Connection> connections = new ConcurrentHashMap<>();
    private final ObjectMapper json;
    private final RoomRepository rooms;

    public ChatWebSocketHandler(ObjectMapper json, RoomRepository rooms) {
        this.json = json;
        this.rooms = rooms;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        ChatPrincipal principal = (ChatPrincipal) session.getAttributes().get(TicketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        if (principal == null || !principal.expiresAt().isAfter(Instant.now())) {
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        session.setTextMessageSizeLimit(4096);
        Connection connection = new Connection(new ConcurrentWebSocketSessionDecorator(session, 5000, 65536), principal);
        connections.put(session.getId(), connection);
        send(connection, Map.of("type", "READY", "userId", principal.id()));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        Connection connection = connections.get(session.getId());
        if (connection == null) { return; }
        if (!connection.principal.expiresAt().isAfter(Instant.now())) {
            close(connection, CloseStatus.POLICY_VIOLATION);
            return;
        }
        try {
            JsonNode input = json.readTree(message.getPayload());
            if (input == null || !input.isObject() || !"JOIN".equals(input.path("type").asText())) {
                error(connection, "unsupported_event", "Send JOIN to subscribe to a room; use HTTP to send messages");
                return;
            }
            JsonNode roomId = input.get("roomId");
            if (roomId == null || !roomId.canConvertToLong() || !roomId.isIntegralNumber() || roomId.asLong() <= 0) {
                error(connection, "validation_failed", "roomId must be a positive integer");
                return;
            }
            if (!rooms.existsById(roomId.asLong())) {
                error(connection, "room_not_found", "Room not found");
                return;
            }
            connection.roomId = roomId.asLong();
            send(connection, Map.of("type", "JOINED", "roomId", connection.roomId));
        } catch (IOException ex) {
            error(connection, "invalid_json", "Send a valid JSON object");
        } catch (RuntimeException ex) {
            error(connection, "service_unavailable", "Unable to join this room right now");
        }
    }

    public void broadcast(MessageView message) {
        connections.values().stream().filter(connection -> Long.valueOf(message.roomId()).equals(connection.roomId))
                .forEach(connection -> {
                    if (connection.principal.expiresAt().isAfter(Instant.now())) {
                        send(connection, Map.of("type", "MESSAGE", "data", message));
                    } else {
                        close(connection, CloseStatus.POLICY_VIOLATION);
                    }
                });
    }

    @Scheduled(fixedDelay = 1000)
    public void closeExpiredSessions() {
        Instant now = Instant.now();
        connections.values().stream().filter(connection -> !connection.principal.expiresAt().isAfter(now))
                .forEach(connection -> close(connection, CloseStatus.POLICY_VIOLATION));
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        connections.remove(session.getId());
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws IOException {
        connections.remove(session.getId());
        session.close(CloseStatus.SERVER_ERROR);
    }

    private void error(Connection connection, String code, String message) {
        send(connection, Map.of("type", "ERROR", "code", code, "message", message));
    }

    private void send(Connection connection, Object payload) {
        try {
            if (connection.session.isOpen()) {
                connection.session.sendMessage(new TextMessage(json.writeValueAsString(payload)));
            }
        } catch (IOException | RuntimeException ex) {
            close(connection, CloseStatus.SESSION_NOT_RELIABLE);
        }
    }

    private void close(Connection connection, CloseStatus status) {
        connections.remove(connection.session.getId());
        try { connection.session.close(status); } catch (IOException ignored) { }
    }

    private static final class Connection {
        private final WebSocketSession session;
        private final ChatPrincipal principal;
        private volatile Long roomId;

        private Connection(WebSocketSession session, ChatPrincipal principal) {
            this.session = session;
            this.principal = principal;
        }
    }
}
