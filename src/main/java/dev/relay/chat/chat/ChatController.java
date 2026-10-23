package dev.relay.chat.chat;

import dev.relay.chat.api.ApiResponse;
import dev.relay.chat.auth.ChatPrincipal;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms")
public class ChatController {
    private final ChatService chat;

    public ChatController(ChatService chat) { this.chat = chat; }

    @GetMapping
    public ApiResponse<List<RoomView>> rooms() { return ApiResponse.ok(chat.rooms()); }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<RoomView> createRoom(@Valid @RequestBody CreateRoomRequest request) {
        return ApiResponse.ok(chat.createRoom(request));
    }

    @GetMapping("/{roomId}/messages")
    public ApiResponse<ChatService.HistoryPage> history(@PathVariable Long roomId,
                                                       @RequestParam(required = false) Long before,
                                                       @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.ok(chat.history(roomId, before, limit));
    }

    @PostMapping("/{roomId}/messages")
    public ApiResponse<MessageView> send(@PathVariable Long roomId, @AuthenticationPrincipal ChatPrincipal principal,
                                         @Valid @RequestBody SendMessageRequest request) {
        return ApiResponse.ok(chat.send(roomId, principal, request));
    }
}
