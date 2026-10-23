package dev.relay.chat.chat;

import dev.relay.chat.auth.UserAccount;
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
import java.util.UUID;

@Entity
@Table(name = "messages", uniqueConstraints = @UniqueConstraint(name = "uq_messages_sender_client", columnNames = {"sender_id", "client_message_id"}))
public class ChatMessage {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "room_id", nullable = false)
    private Room room;
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sender_id", nullable = false)
    private UserAccount sender;
    @Column(nullable = false, length = 2000)
    private String content;
    @Column(name = "client_message_id", nullable = false)
    private UUID clientMessageId;
    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ChatMessage() { }

    public ChatMessage(Room room, UserAccount sender, String content, UUID clientMessageId) {
        this.room = room;
        this.sender = sender;
        this.content = content;
        this.clientMessageId = clientMessageId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Room getRoom() { return room; }
    public UserAccount getSender() { return sender; }
    public String getContent() { return content; }
    public UUID getClientMessageId() { return clientMessageId; }
    public Instant getCreatedAt() { return createdAt; }
}
