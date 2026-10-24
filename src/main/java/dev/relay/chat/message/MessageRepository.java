package dev.relay.chat.message;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MessageRepository extends JpaRepository<ChatMessage, Long> {
    @EntityGraph(attributePaths = {"sender", "room"})
    Optional<ChatMessage> findBySender_IdAndClientMessageId(long senderId, UUID clientMessageId);

    @EntityGraph(attributePaths = {"sender", "room"})
    List<ChatMessage> findByRoom_IdOrderByIdDesc(long roomId, Pageable pageable);

    @EntityGraph(attributePaths = {"sender", "room"})
    List<ChatMessage> findByRoom_IdAndIdLessThanOrderByIdDesc(long roomId, long before, Pageable pageable);

    @EntityGraph(attributePaths = {"sender", "room"})
    List<ChatMessage> findByRoom_IdAndIdGreaterThanOrderByIdAsc(long roomId, long after, Pageable pageable);
}
