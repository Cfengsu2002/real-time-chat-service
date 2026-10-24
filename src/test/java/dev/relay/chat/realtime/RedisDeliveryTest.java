package dev.relay.chat.realtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.message.MessageCreatedEvent;
import dev.relay.chat.message.MessageView;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisDeliveryTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Instant now = Instant.parse("2026-10-24T12:00:00Z");

    @Test
    @SuppressWarnings("unchecked")
    void redisTicketUsesTtlAndAtomicGetDeleteAndPreservesJwtExpiry() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ValueOperations<String, String> values = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        RedisTicketStore store = new RedisTicketStore(redis, json, Clock.fixed(now, ZoneOffset.UTC));
        TicketStore.TicketGrant grant = new TicketStore.TicketGrant(new ChatPrincipal(42, "alex", now.plusSeconds(3600)),
                now.plusSeconds(30));
        store.put("opaque-ticket", grant);
        ArgumentCaptor<String> payload = ArgumentCaptor.forClass(String.class);
        verify(values).set(eq("relay:v2:ws-ticket:opaque-ticket"), payload.capture(), eq(Duration.ofSeconds(30)));
        when(values.getAndDelete("relay:v2:ws-ticket:opaque-ticket")).thenReturn(payload.getValue(), null);
        assertThat(store.consume("opaque-ticket")).contains(grant);
        assertThat(store.consume("opaque-ticket")).isEmpty();
    }

    @Test
    void redisPublishesSerializedMessageAndOnlySubscriberDispatchesLocally() throws Exception {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        ChatWebSocketHandler sockets = mock(ChatWebSocketHandler.class);
        RedisMessageBus bus = new RedisMessageBus(redis, json, sockets);
        MessageView message = message();
        bus.publish(message);
        ArgumentCaptor<Object> payload = ArgumentCaptor.forClass(Object.class);
        verify(redis).convertAndSend(eq(RedisMessageBus.CHANNEL), payload.capture());
        verify(sockets, never()).broadcast(any());
        Message frame = mock(Message.class);
        when(frame.getBody()).thenReturn(payload.getValue().toString().getBytes(StandardCharsets.UTF_8));
        bus.onMessage(frame, null);
        verify(sockets).broadcast(message);
    }

    @Test
    void invalidAndOversizedPubsubFramesAreIgnored() {
        ChatWebSocketHandler sockets = mock(ChatWebSocketHandler.class);
        RedisMessageBus bus = new RedisMessageBus(mock(StringRedisTemplate.class), json, sockets);
        Message invalid = mock(Message.class);
        when(invalid.getBody()).thenReturn("{invalid".getBytes(StandardCharsets.UTF_8));
        Message oversized = mock(Message.class);
        when(oversized.getBody()).thenReturn(new byte[16385]);
        assertThatCode(() -> bus.onMessage(invalid, null)).doesNotThrowAnyException();
        assertThatCode(() -> bus.onMessage(oversized, null)).doesNotThrowAnyException();
        verify(sockets, never()).broadcast(any());
    }

    @Test
    void deliveryFailureAfterCommitDoesNotTurnPersistedSendIntoFailedRequest() {
        MessageBus bus = mock(MessageBus.class);
        doThrow(new IllegalStateException("Redis offline")).when(bus).publish(any());
        CommittedMessagePublisher publisher = new CommittedMessagePublisher(bus);
        assertThatCode(() -> publisher.onCreated(new MessageCreatedEvent(message()))).doesNotThrowAnyException();
    }

    private MessageView message() {
        return new MessageView(1, 1, 42, "alex", UUID.randomUUID(), "Hello, Redis", now);
    }
}
