package dev.relay.chat.auth;

import dev.relay.chat.api.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/register")
    public ResponseEntity<ApiResponse<JwtService.TokenResult>> register(@Valid @RequestBody CredentialsRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(auth.register(request)));
    }

    @PostMapping("/login")
    public ApiResponse<JwtService.TokenResult> login(@Valid @RequestBody CredentialsRequest request) {
        return ApiResponse.success(auth.login(request));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me(@AuthenticationPrincipal ChatPrincipal principal) {
        return ApiResponse.success(auth.me(principal));
    }
}
