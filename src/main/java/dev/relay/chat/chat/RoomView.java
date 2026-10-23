package dev.relay.chat.chat;

import java.time.Instant;

public record RoomView(Long id, String name, String description, Instant createdAt) {
    public static RoomView from(Room room) {
        return new RoomView(room.getId(), room.getName(), room.getDescription(), room.getCreatedAt());
    }
}
