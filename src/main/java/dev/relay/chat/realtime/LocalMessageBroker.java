package dev.relay.chat.realtime;

import dev.relay.chat.chat.MessageView;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile({"demo", "test"})
public class LocalMessageBroker implements MessageBroker {
    private final ChatWebSocketHandler sockets;

    public LocalMessageBroker(ChatWebSocketHandler sockets) { this.sockets = sockets; }

    @Override
    public void publish(MessageView message) { sockets.broadcast(message); }
}
