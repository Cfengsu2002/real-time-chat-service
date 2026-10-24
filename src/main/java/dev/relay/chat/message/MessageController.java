package dev.relay.chat.message;

import dev.relay.chat.api.ApiResponse;
import dev.relay.chat.auth.ChatPrincipal;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/rooms/{roomId}/messages")
public class MessageController {
    private final MessageService messages;

    public MessageController(MessageService messages) {
        this.messages = messages;
    }

    @GetMapping
    public ApiResponse<MessagePage> history(@PathVariable long roomId,
                                             @RequestParam(required = false) Long before,
                                             @RequestParam(required = false) Long after,
                                             @RequestParam(defaultValue = "50") int limit) {
        return ApiResponse.success(messages.history(roomId, before, after, limit));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<MessageView>> send(@PathVariable long roomId,
                                                          @AuthenticationPrincipal ChatPrincipal principal,
                                                          @Valid @RequestBody SendMessageRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(messages.send(roomId, principal, request)));
    }
}
