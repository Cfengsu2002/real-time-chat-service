package dev.relay.chat.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.api.ApiException;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "app.realtime.transport", havingValue = "redis", matchIfMissing = true)
public class RedisTicketStore implements TicketStore {
    private static final String PREFIX = "relay:v2:ws-ticket:";
    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Clock clock;

    public RedisTicketStore(StringRedisTemplate redis, ObjectMapper json, Clock clock) {
        this.redis = redis;
        this.json = json;
        this.clock = clock;
    }

    @Override
    public void put(String ticket, TicketGrant grant) {
        Duration ttl = Duration.between(clock.instant(), grant.expiresAt());
        if (ttl.isNegative() || ttl.isZero()) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "Sign in to connect");
        }
        try {
            redis.opsForValue().set(PREFIX + ticket, json.writeValueAsString(grant), ttl);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Cannot encode connection ticket", exception);
        } catch (org.springframework.dao.DataAccessException exception) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "realtime_unavailable", "Live connections are temporarily unavailable");
        }
    }

    @Override
    public Optional<TicketGrant> consume(String ticket) {
        // GETDEL 在 Redis 中原子执行，避免跨实例同时复用票据。
        String value = redis.opsForValue().getAndDelete(PREFIX + ticket);
        if (value == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(value, TicketGrant.class));
        } catch (JsonProcessingException exception) {
            return Optional.empty();
        }
    }
}
