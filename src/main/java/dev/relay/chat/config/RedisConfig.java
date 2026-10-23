package dev.relay.chat.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.chat.MessageView;
import dev.relay.chat.realtime.ChatWebSocketHandler;
import dev.relay.chat.realtime.RedisMessageBroker;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;

@Configuration
@Profile("!demo & !test")
public class RedisConfig {
    private static final Logger log = LoggerFactory.getLogger(RedisConfig.class);

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                      ObjectMapper json, ChatWebSocketHandler sockets) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener((message, pattern) -> {
            try {
                sockets.broadcast(json.readValue(message.getBody(), MessageView.class));
            } catch (IOException | RuntimeException ex) {
                log.warn("Invalid realtime event ignored: {}", ex.getClass().getSimpleName());
            }
        }, new ChannelTopic(RedisMessageBroker.CHANNEL));
        return container;
    }
}
