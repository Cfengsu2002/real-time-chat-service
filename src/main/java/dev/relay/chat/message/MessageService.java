package dev.relay.chat.message;

import dev.relay.chat.api.ApiException;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.auth.UserAccount;
import dev.relay.chat.auth.UserRepository;
import dev.relay.chat.room.Room;
import dev.relay.chat.room.RoomRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class MessageService {
    private final MessageRepository messages;
    private final RoomRepository rooms;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;
    private final EntityManager entityManager;

    public MessageService(MessageRepository messages, RoomRepository rooms, UserRepository users,
                          ApplicationEventPublisher events, PlatformTransactionManager transactionManager,
                          EntityManager entityManager) {
        this.messages = messages;
        this.rooms = rooms;
        this.users = users;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
        this.entityManager = entityManager;
    }

    @Transactional(readOnly = true)
    public MessagePage history(long roomId, Long before, Long after, int limit) {
        if (limit < 1 || limit > 100) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_limit", "Limit must be between 1 and 100");
        }
        if ((before != null && after != null) || (before != null && before < 1)
                || (after != null && after < 0)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_cursor",
                    "Use one cursor: before must be positive and after must be nonnegative");
        }
        requireRoom(roomId);
        PageRequest page = PageRequest.of(0, limit + 1);
        List<ChatMessage> found;
        if (after != null) {
            found = messages.findByRoom_IdAndIdGreaterThanOrderByIdAsc(roomId, after, page);
        } else if (before != null) {
            found = messages.findByRoom_IdAndIdLessThanOrderByIdDesc(roomId, before, page);
        } else {
            found = messages.findByRoom_IdOrderByIdDesc(roomId, page);
        }
        boolean hasMore = found.size() > limit;
        List<MessageView> views = new ArrayList<>(found.stream().limit(limit).map(MessageView::from).toList());
        if (after == null) {
            Collections.reverse(views);
        }
        return new MessagePage(List.copyOf(views), hasMore);
    }

    public MessageView send(long roomId, ChatPrincipal principal, SendMessageRequest request) {
        String content = request.content().strip();
        try {
            return transactions.execute(status -> save(roomId, principal, request, content));
        } catch (DataIntegrityViolationException exception) {
            // 唯一约束冲突后开启新事务读取已提交的消息，避免在回滚事务中继续查询。
            Optional<MessageView> existing = transactions.execute(status -> messages
                    .findBySender_IdAndClientMessageId(principal.id(), request.clientMessageId())
                    .map(message -> matchingRetry(message, roomId, content)));
            if (existing != null && existing.isPresent()) {
                return existing.get();
            }
            throw exception;
        }
    }

    private MessageView save(long roomId, ChatPrincipal principal, SendMessageRequest request, String content) {
        // 同一房间按提交顺序分配消息 ID，避免补读游标跳过尚未提交的较小 ID。
        Room room = entityManager.find(Room.class, roomId, LockModeType.PESSIMISTIC_WRITE);
        if (room == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "room_not_found", "Room not found");
        }
        Optional<ChatMessage> existing = messages.findBySender_IdAndClientMessageId(
                principal.id(), request.clientMessageId());
        if (existing.isPresent()) {
            return matchingRetry(existing.get(), roomId, content);
        }
        UserAccount user = users.findById(principal.id()).orElseThrow(() -> new ApiException(
                HttpStatus.UNAUTHORIZED, "invalid_token", "Account no longer exists"));
        ChatMessage saved = messages.saveAndFlush(new ChatMessage(room, user, request.clientMessageId(), content));
        MessageView view = MessageView.from(saved);
        events.publishEvent(new MessageCreatedEvent(view));
        return view;
    }

    private MessageView matchingRetry(ChatMessage existing, long roomId, String content) {
        if (existing.getRoom().getId() != roomId || !existing.getContent().equals(content)) {
            throw new ApiException(HttpStatus.CONFLICT, "message_id_conflict",
                    "Client message ID was already used for a different message");
        }
        return MessageView.from(existing);
    }

    private Room requireRoom(long roomId) {
        return rooms.findById(roomId).orElseThrow(() -> new ApiException(
                HttpStatus.NOT_FOUND, "room_not_found", "Room not found"));
    }
}
