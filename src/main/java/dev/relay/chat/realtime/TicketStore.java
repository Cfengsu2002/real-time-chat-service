package dev.relay.chat.realtime;

import java.time.Duration;

public interface TicketStore {
    void put(String key, String value, Duration ttl);
    String take(String key);
}
