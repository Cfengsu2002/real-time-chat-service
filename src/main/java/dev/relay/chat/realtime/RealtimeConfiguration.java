package dev.relay.chat.realtime;

import java.time.Clock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.ChannelTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class RealtimeConfiguration implements WebSocketConfigurer {
    private final ChatWebSocketHandler handler;
    private final TicketHandshakeInterceptor handshake;
    private final OriginPolicy origins;

    public RealtimeConfiguration(ChatWebSocketHandler handler, TicketHandshakeInterceptor handshake, OriginPolicy origins) {
        this.handler = handler;
        this.handshake = handshake;
        this.origins = origins;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(handler, "/ws").addInterceptors(handshake).setAllowedOrigins(origins.configuredOrigins());
    }

    @Bean
    @ConditionalOnMissingBean
    public static Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    @ConditionalOnProperty(name = "app.realtime.transport", havingValue = "redis", matchIfMissing = true)
    public RedisMessageListenerContainer redisMessageListenerContainer(RedisConnectionFactory connectionFactory,
                                                                      RedisMessageBus bus) {
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.addMessageListener(bus, new ChannelTopic(RedisMessageBus.CHANNEL));
        return container;
    }
}
