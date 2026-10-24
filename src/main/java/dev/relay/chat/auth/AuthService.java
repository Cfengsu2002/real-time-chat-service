package dev.relay.chat.auth;

import dev.relay.chat.api.ApiException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AuthService {
    private final UserRepository users;
    private final PasswordEncoder passwords;
    private final JwtService jwt;
    private final String missingUserHash;

    public AuthService(UserRepository users, PasswordEncoder passwords, JwtService jwt) {
        this.users = users;
        this.passwords = passwords;
        this.jwt = jwt;
        this.missingUserHash = passwords.encode("padding-for-nonexistent-user-checks");
    }

    @Transactional
    public JwtService.TokenResult register(CredentialsRequest request) {
        validatePasswordBytes(request.password());
        String username = request.username().toLowerCase(Locale.ROOT);
        if (users.findByUsername(username).isPresent()) {
            throw new ApiException(HttpStatus.CONFLICT, "username_taken", "This username is already taken");
        }
        UserAccount user = users.saveAndFlush(new UserAccount(username, passwords.encode(request.password())));
        return jwt.issue(user);
    }

    @Transactional(readOnly = true)
    public JwtService.TokenResult login(CredentialsRequest request) {
        validatePasswordBytes(request.password());
        UserAccount user = users.findByUsername(request.username().toLowerCase(Locale.ROOT)).orElse(null);
        // 不存在的账号也执行 BCrypt，减少账号是否存在带来的耗时差异。
        boolean matches = passwords.matches(request.password(), user == null ? missingUserHash : user.getPasswordHash());
        if (user == null || !matches) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_credentials", "Invalid username or password");
        }
        return jwt.issue(user);
    }

    @Transactional(readOnly = true)
    public UserView me(ChatPrincipal principal) {
        return users.findById(principal.id()).map(UserView::from)
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "User no longer exists"));
    }

    private void validatePasswordBytes(String password) {
        if (password.getBytes(StandardCharsets.UTF_8).length > 72) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "validation_failed", "Password must not exceed 72 UTF-8 bytes");
        }
    }
}
