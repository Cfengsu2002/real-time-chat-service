package dev.relay.chat.chat;

import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface RoomRepository extends JpaRepository<Room, Long> {
    List<Room> findAllByOrderByIdAsc();
    boolean existsByName(String name);
}
