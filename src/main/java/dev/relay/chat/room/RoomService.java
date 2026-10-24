package dev.relay.chat.room;

import dev.relay.chat.api.ApiException;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RoomService {
    private final RoomRepository rooms;

    public RoomService(RoomRepository rooms) {
        this.rooms = rooms;
    }

    @Transactional(readOnly = true)
    public List<RoomView> list() {
        return rooms.findAllByOrderByIdAsc().stream().map(RoomView::from).toList();
    }

    @Transactional
    public RoomView create(CreateRoomRequest request) {
        if (rooms.existsByName(request.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "room_exists", "A room with this name already exists");
        }
        String description = request.description() == null ? "" : request.description().strip();
        return RoomView.from(rooms.saveAndFlush(new Room(request.name(), description)));
    }
}
