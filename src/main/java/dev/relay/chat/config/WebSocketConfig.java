package dev.relay.chat.config;

import dev.relay.chat.realtime.ChatWebSocketHandler;
import dev.relay.chat.realtime.TicketHandshakeInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ChatWebSocketHandler handler;
    private final TicketHandshakeInterceptor tickets;

    public WebSocketConfig(ChatWebSocketHandler handler, TicketHandshakeInterceptor tickets) {
        this.handler = handler;
        this.tickets = tickets;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        // Origin 校验由票据拦截器执行，同时比较协议、主机和端口。
        registry.addHandler(handler, "/ws").addInterceptors(tickets).setAllowedOriginPatterns("*");
    }
}
