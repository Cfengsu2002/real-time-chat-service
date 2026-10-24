package dev.relay.chat.realtime;

import dev.relay.chat.message.MessageView;

public interface MessageBus {
    void publish(MessageView message);
}
