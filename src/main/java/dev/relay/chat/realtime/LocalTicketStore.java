package dev.relay.chat.realtime;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@Profile({"demo", "test"})
public class LocalTicketStore implements TicketStore {
    private final ConcurrentHashMap<String, Entry> values = new ConcurrentHashMap<>();

    @Override
    public void put(String key, String value, Duration ttl) {
        values.put(key, new Entry(value, Instant.now().plus(ttl)));
    }

    @Override
    public String take(String key) {
        Entry entry = values.remove(key);
        return entry != null && entry.expiresAt().isAfter(Instant.now()) ? entry.value() : null;
    }

    @Scheduled(fixedDelay = 30000)
    void removeExpired() {
        Instant now = Instant.now();
        values.entrySet().removeIf(entry -> !entry.getValue().expiresAt().isAfter(now));
    }

    private record Entry(String value, Instant expiresAt) { }
}
