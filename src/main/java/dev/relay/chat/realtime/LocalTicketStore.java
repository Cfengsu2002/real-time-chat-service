package dev.relay.chat.realtime;

import dev.relay.chat.api.ApiException;
import java.time.Clock;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.realtime.transport", havingValue = "local")
public class LocalTicketStore implements TicketStore {
    private final ConcurrentHashMap<String, TicketGrant> tickets = new ConcurrentHashMap<>();
    private final Clock clock;

    public LocalTicketStore(Clock clock) {
        this.clock = clock;
    }

    @Override
    public void put(String ticket, TicketGrant grant) {
        tickets.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(clock.instant()));
        if (tickets.size() >= 10000) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "ticket_capacity", "Try connecting again shortly");
        }
        tickets.put(ticket, grant);
    }

    @Override
    public Optional<TicketGrant> consume(String ticket) {
        return Optional.ofNullable(tickets.remove(ticket));
    }
}
