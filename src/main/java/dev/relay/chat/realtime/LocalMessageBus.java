package dev.relay.chat.realtime;

import dev.relay.chat.message.MessageView;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.realtime.transport", havingValue = "local")
public class LocalMessageBus implements MessageBus {
    private final ChatWebSocketHandler sockets;

    public LocalMessageBus(ChatWebSocketHandler sockets) {
        this.sockets = sockets;
    }

    @Override
    public void publish(MessageView message) {
        sockets.broadcast(message);
    }
}
