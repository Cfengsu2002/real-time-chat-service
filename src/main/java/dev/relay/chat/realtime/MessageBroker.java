package dev.relay.chat.realtime;

import dev.relay.chat.chat.MessageView;

public interface MessageBroker {
    void publish(MessageView message);
}
