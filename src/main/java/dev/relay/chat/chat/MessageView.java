package dev.relay.chat.chat;

import dev.relay.chat.auth.UserView;
import java.time.Instant;
import java.util.UUID;

public record MessageView(Long id, Long roomId, String content, UserView sender, Instant createdAt, UUID clientMessageId) {
    public static MessageView from(ChatMessage message) {
        return new MessageView(message.getId(), message.getRoom().getId(), message.getContent(),
                UserView.from(message.getSender()), message.getCreatedAt(), message.getClientMessageId());
    }
}
