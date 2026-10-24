package dev.relay.chat.message;

import dev.relay.chat.auth.UserAccount;
import dev.relay.chat.room.Room;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "messages", uniqueConstraints = @UniqueConstraint(
        name = "messages_sender_client_unique", columnNames = {"sender_id", "client_message_id"}))
public class ChatMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private UserAccount sender;

    @Column(name = "client_message_id", nullable = false)
    private UUID clientMessageId;

    @Column(nullable = false, length = 2000)
    private String content;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatMessage() { }

    public ChatMessage(Room room, UserAccount sender, UUID clientMessageId, String content) {
        this.room = room;
        this.sender = sender;
        this.clientMessageId = clientMessageId;
        this.content = content;
        this.createdAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    public Long getId() { return id; }
    public Room getRoom() { return room; }
    public UserAccount getSender() { return sender; }
    public UUID getClientMessageId() { return clientMessageId; }
    public String getContent() { return content; }
    public Instant getCreatedAt() { return createdAt; }
}
