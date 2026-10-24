package dev.relay.chat.realtime;

import dev.relay.chat.api.ApiResponse;
import dev.relay.chat.auth.ChatPrincipal;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TicketController {
    private final TicketService tickets;

    public TicketController(TicketService tickets) {
        this.tickets = tickets;
    }

    @PostMapping("/api/ws/tickets")
    public ResponseEntity<ApiResponse<TicketService.TicketView>> issue(@AuthenticationPrincipal ChatPrincipal principal) {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(ApiResponse.success(tickets.issue(principal)));
    }
}
