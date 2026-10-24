package dev.relay.chat.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.message.MessageView;
import dev.relay.chat.room.RoomRepository;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.ConcurrentWebSocketSessionDecorator;
import org.springframework.web.socket.handler.TextWebSocketHandler;

@Component
public class ChatWebSocketHandler extends TextWebSocketHandler {
    private static final CloseStatus AUTH_EXPIRED = new CloseStatus(4401, "Sign in again");
    private final ConcurrentHashMap<String, LiveSession> sessions = new ConcurrentHashMap<>();
    private final ScheduledThreadPoolExecutor expirations = expiryScheduler();
    private final ObjectMapper json;
    private final RoomRepository rooms;
    private final Clock clock;

    public ChatWebSocketHandler(ObjectMapper json, RoomRepository rooms, Clock clock) {
        this.json = json;
        this.rooms = rooms;
        this.clock = clock;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession raw) throws Exception {
        Object value = raw.getAttributes().get(TicketHandshakeInterceptor.PRINCIPAL_ATTRIBUTE);
        if (!(value instanceof ChatPrincipal principal) || !principal.expiresAt().isAfter(clock.instant())) {
            raw.close(AUTH_EXPIRED);
            return;
        }
        if (sessions.size() >= 10000) {
            raw.close(CloseStatus.SERVICE_OVERLOAD);
            return;
        }
        raw.setTextMessageSizeLimit(4096);
        LiveSession live = new LiveSession(new ConcurrentWebSocketSessionDecorator(raw, 10000, 65536), principal);
        sessions.put(raw.getId(), live);
        long delay = Math.max(0, Duration.between(clock.instant(), principal.expiresAt()).toMillis());
        live.expiry = expirations.schedule(() -> close(live, AUTH_EXPIRED), delay, TimeUnit.MILLISECONDS);
        if (sessions.get(raw.getId()) != live) {
            live.expiry.cancel(false);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession raw, TextMessage frame) {
        LiveSession live = sessions.get(raw.getId());
        if (live == null) {
            return;
        }
        if (!live.principal.expiresAt().isAfter(clock.instant())) {
            close(live, AUTH_EXPIRED);
            return;
        }
        if (frame.getPayloadLength() > 4096) {
            close(live, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        try {
            JsonNode request = json.readTree(frame.getPayload());
            if (request == null || !request.isObject() || !request.path("type").isTextual()) {
                invalid(live);
                return;
            }
            switch (request.path("type").asText()) {
                case "ping" -> {
                    if (request.size() != 1) {
                        invalid(live);
                    } else {
                        send(live, Map.of("type", "pong"));
                    }
                }
                case "subscribe" -> {
                    JsonNode id = request.path("roomId");
                    if (request.size() != 2 || !id.isIntegralNumber() || !id.canConvertToLong() || id.asLong() < 1) {
                        invalid(live);
                    } else if (!rooms.existsById(id.asLong())) {
                        send(live, Map.of("type", "error", "code", "room_not_found", "message", "Room does not exist"));
                    } else {
                        live.roomId = id.asLong();
                        send(live, Map.of("type", "subscribed", "roomId", id.asLong()));
                    }
                }
                default -> invalid(live);
            }
        } catch (JsonProcessingException exception) {
            invalid(live);
        }
    }

    public void broadcast(MessageView message) {
        Map<String, Object> frame = Map.of("type", "message", "message", message);
        for (LiveSession live : sessions.values()) {
            if (!live.principal.expiresAt().isAfter(clock.instant())) {
                close(live, AUTH_EXPIRED);
            } else if (live.roomId != null && live.roomId == message.roomId()) {
                send(live, frame);
            }
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession raw, CloseStatus status) {
        LiveSession live = sessions.remove(raw.getId());
        if (live != null && live.expiry != null) {
            live.expiry.cancel(false);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession raw, Throwable exception) {
        LiveSession live = sessions.get(raw.getId());
        if (live != null) {
            close(live, CloseStatus.SERVER_ERROR);
        }
    }

    private void invalid(LiveSession live) {
        send(live, Map.of("type", "error", "code", "invalid_frame", "message", "Send a subscribe or ping frame"));
    }

    private void send(LiveSession live, Object frame) {
        try {
            if (live.socket.isOpen()) {
                live.socket.sendMessage(new TextMessage(json.writeValueAsString(frame)));
            }
        } catch (IOException | RuntimeException exception) {
            close(live, CloseStatus.SERVER_ERROR);
        }
    }

    private void close(LiveSession live, CloseStatus status) {
        sessions.remove(live.socket.getId(), live);
        if (live.expiry != null) {
            live.expiry.cancel(false);
        }
        try {
            live.socket.close(status);
        } catch (IOException | RuntimeException ignored) {
            // 连接已失效时无需重复发送关闭帧。
        }
    }

    @PreDestroy
    public void shutdown() {
        sessions.values().forEach(live -> close(live, CloseStatus.GOING_AWAY));
        expirations.shutdownNow();
    }

    private static ScheduledThreadPoolExecutor expiryScheduler() {
        ScheduledThreadPoolExecutor scheduler = new ScheduledThreadPoolExecutor(1, task -> {
            Thread thread = new Thread(task, "relay-websocket-expiry");
            thread.setDaemon(true);
            return thread;
        });
        scheduler.setRemoveOnCancelPolicy(true);
        return scheduler;
    }

    private static final class LiveSession {
        private final WebSocketSession socket;
        private final ChatPrincipal principal;
        private volatile Long roomId;
        private volatile ScheduledFuture<?> expiry;

        private LiveSession(WebSocketSession socket, ChatPrincipal principal) {
            this.socket = socket;
            this.principal = principal;
        }
    }
}
