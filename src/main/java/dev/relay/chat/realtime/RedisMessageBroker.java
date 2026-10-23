package dev.relay.chat.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.chat.MessageView;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!demo & !test")
public class RedisMessageBroker implements MessageBroker {
    public static final String CHANNEL = "relay:messages:v1";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;

    public RedisMessageBroker(StringRedisTemplate redis, ObjectMapper json) {
        this.redis = redis;
        this.json = json;
    }

    @Override
    public void publish(MessageView message) {
        try {
            redis.convertAndSend(CHANNEL, json.writeValueAsString(message));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize realtime event", ex);
        }
    }
}
