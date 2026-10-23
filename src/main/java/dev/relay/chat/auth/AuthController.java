package dev.relay.chat.auth;

import dev.relay.chat.api.ApiResponse;
import dev.relay.chat.realtime.TicketService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;
    private final TicketService tickets;

    public AuthController(AuthService auth, TicketService tickets) {
        this.auth = auth;
        this.tickets = tickets;
    }

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<JwtService.TokenResult> register(@Valid @RequestBody CredentialsRequest request) {
        return ApiResponse.ok(auth.register(request));
    }

    @PostMapping("/login")
    public ApiResponse<JwtService.TokenResult> login(@Valid @RequestBody CredentialsRequest request) {
        return ApiResponse.ok(auth.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal ChatPrincipal principal) {
        return ApiResponse.ok(auth.me(principal));
    }

    @PostMapping("/ws-ticket")
    public ApiResponse<TicketService.TicketView> ticket(@AuthenticationPrincipal ChatPrincipal principal) {
        return ApiResponse.ok(tickets.issue(principal));
    }
}
