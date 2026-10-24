package dev.relay.chat.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.message.MessageView;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.realtime.transport", havingValue = "redis", matchIfMissing = true)
public class RedisMessageBus implements MessageBus, MessageListener {
    public static final String CHANNEL = "relay:v2:messages";
    private static final Logger logger = LoggerFactory.getLogger(RedisMessageBus.class);
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final ChatWebSocketHandler sockets;

    public RedisMessageBus(StringRedisTemplate redis, ObjectMapper json, ChatWebSocketHandler sockets) {
        this.redis = redis;
        this.json = json;
        this.sockets = sockets;
    }

    @Override
    public void publish(MessageView message) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(message));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode message delivery", exception);
        }
    }

    @Override
    public void onMessage(Message frame, byte[] pattern) {
        if (frame.getBody().length > 16384) {
            logger.warn("Ignored oversized message delivery frame");
            return;
        }
        try {
            sockets.broadcast(json.readValue(frame.getBody(), MessageView.class));
        } catch (IOException | RuntimeException exception) {
            logger.warn("Ignored invalid message delivery frame");
        }
    }
}
