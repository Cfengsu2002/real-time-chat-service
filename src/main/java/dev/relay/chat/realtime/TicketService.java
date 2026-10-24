package dev.relay.chat.realtime;

import dev.relay.chat.api.ApiException;
import dev.relay.chat.auth.ChatPrincipal;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.util.Base64;
import java.util.Optional;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TicketService {
    private final SecureRandom random = new SecureRandom();
    private final TicketStore store;
    private final Clock clock;

    public TicketService(TicketStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    public TicketView issue(ChatPrincipal principal) {
        Instant now = clock.instant();
        if (!principal.expiresAt().isAfter(now)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "Sign in to connect");
        }
        Instant expiresAt = now.plusSeconds(30);
        if (principal.expiresAt().isBefore(expiresAt)) {
            expiresAt = principal.expiresAt();
        }
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        store.put(ticket, new TicketStore.TicketGrant(principal, expiresAt));
        return new TicketView(ticket, expiresAt);
    }

    public Optional<ChatPrincipal> consume(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        return store.consume(ticket)
                .filter(grant -> grant.expiresAt().isAfter(now) && grant.principal().expiresAt().isAfter(now))
                .map(TicketStore.TicketGrant::principal);
    }

    public record TicketView(String ticket, Instant expiresAt) { }
}
