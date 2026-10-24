package dev.relay.chat.message;

import java.time.Instant;
import java.util.UUID;

public record MessageView(long id, long roomId, long userId, String username, UUID clientMessageId,
                          String content, Instant createdAt) {
    public static MessageView from(ChatMessage message) {
        return new MessageView(message.getId(), message.getRoom().getId(), message.getSender().getId(),
                message.getSender().getUsername(), message.getClientMessageId(), message.getContent(),
                message.getCreatedAt());
    }
}
