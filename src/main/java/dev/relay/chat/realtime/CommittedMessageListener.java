package dev.relay.chat.realtime;

import dev.relay.chat.chat.MessageSaved;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

@Component
public class CommittedMessageListener {
    private static final Logger log = LoggerFactory.getLogger(CommittedMessageListener.class);
    private final MessageBroker broker;

    public CommittedMessageListener(MessageBroker broker) { this.broker = broker; }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(MessageSaved event) {
        try {
            broker.publish(event.message());
        } catch (RuntimeException ex) {
            // 持久化已经成功；广播失败时客户端可通过历史接口补齐。
            log.error("Realtime publish failed for message {}: {}", event.message().id(), ex.getClass().getSimpleName());
        }
    }
}
