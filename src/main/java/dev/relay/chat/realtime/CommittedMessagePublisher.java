package dev.relay.chat.realtime;

import dev.relay.chat.message.MessageCreatedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CommittedMessagePublisher {
    private static final Logger logger = LoggerFactory.getLogger(CommittedMessagePublisher.class);
    private final MessageBus bus;

    public CommittedMessagePublisher(MessageBus bus) {
        this.bus = bus;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCreated(MessageCreatedEvent event) {
        try {
            bus.publish(event.message());
        } catch (RuntimeException exception) {
            // 消息已经提交，推送失败时仍可由历史接口读取，不记录消息正文或凭据。
            logger.warn("Live delivery unavailable for stored message {}", event.message().id());
        }
    }
}
