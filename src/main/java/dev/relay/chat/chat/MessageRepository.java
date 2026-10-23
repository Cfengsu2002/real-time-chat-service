package dev.relay.chat.chat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MessageRepository extends JpaRepository<ChatMessage, Long> {
    @EntityGraph(attributePaths = {"sender", "room"})
    Optional<ChatMessage> findBySenderIdAndClientMessageId(Long senderId, UUID clientMessageId);

    @EntityGraph(attributePaths = {"sender", "room"})
    @Query("select m from ChatMessage m where m.room.id = :roomId and m.id < :before order by m.id desc")
    List<ChatMessage> history(@Param("roomId") Long roomId, @Param("before") long before, Pageable pageable);
}
