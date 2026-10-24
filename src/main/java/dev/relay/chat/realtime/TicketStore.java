package dev.relay.chat.realtime;

import dev.relay.chat.auth.ChatPrincipal;
import java.time.Instant;
import java.util.Optional;

public interface TicketStore {
    void put(String ticket, TicketGrant grant);
    Optional<TicketGrant> consume(String ticket);

    record TicketGrant(ChatPrincipal principal, Instant expiresAt) { }
}
