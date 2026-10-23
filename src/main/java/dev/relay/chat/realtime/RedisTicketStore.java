package dev.relay.chat.realtime;

import java.time.Duration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
@Profile("!demo & !test")
public class RedisTicketStore implements TicketStore {
    private final StringRedisTemplate redis;

    public RedisTicketStore(StringRedisTemplate redis) { this.redis = redis; }

    @Override
    public void put(String key, String value, Duration ttl) {
        redis.opsForValue().set(key, value, ttl);
    }

    @Override
    public String take(String key) {
        return redis.opsForValue().getAndDelete(key);
    }
}
