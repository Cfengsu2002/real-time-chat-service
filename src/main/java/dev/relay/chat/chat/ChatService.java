package dev.relay.chat.chat;

import dev.relay.chat.api.ApiException;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.auth.UserAccount;
import dev.relay.chat.auth.UserRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ChatService {
    private final RoomRepository rooms;
    private final MessageRepository messages;
    private final UserRepository users;
    private final ApplicationEventPublisher events;
    private final TransactionTemplate transactions;

    public ChatService(RoomRepository rooms, MessageRepository messages, UserRepository users,
                       ApplicationEventPublisher events, PlatformTransactionManager transactionManager) {
        this.rooms = rooms;
        this.messages = messages;
        this.users = users;
        this.events = events;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Transactional(readOnly = true)
    public List<RoomView> rooms() {
        return rooms.findAllByOrderByIdAsc().stream().map(RoomView::from).toList();
    }

    @Transactional
    public RoomView createRoom(CreateRoomRequest request) {
        if (rooms.existsByName(request.name())) {
            throw new ApiException(HttpStatus.CONFLICT, "room_exists", "A room with this name already exists");
        }
        return RoomView.from(rooms.saveAndFlush(new Room(request.name(), request.description() == null ? "" : request.description().strip())));
    }

    @Transactional(readOnly = true)
    public HistoryPage history(Long roomId, Long before, int limit) {
        requireRoom(roomId);
        if (limit < 1 || limit > 100 || (before != null && before < 1)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "invalid_pagination", "limit must be 1–100; before must be a positive message ID");
        }
        List<ChatMessage> rows = messages.history(roomId, before == null ? Long.MAX_VALUE : before, PageRequest.of(0, limit + 1));
        boolean hasMore = rows.size() > limit;
        List<MessageView> items = new ArrayList<>(rows.stream().limit(limit).map(MessageView::from).toList());
        Collections.reverse(items);
        Long nextBefore = hasMore && !items.isEmpty() ? items.get(0).id() : null;
        return new HistoryPage(items, hasMore, nextBefore);
    }

    public MessageView send(Long roomId, ChatPrincipal principal, SendMessageRequest request) {
        try {
            return transactions.execute(status -> {
                ChatMessage existing = messages.findBySenderIdAndClientMessageId(principal.id(), request.clientMessageId()).orElse(null);
                if (existing != null) {
                    return checkRetry(existing, roomId, request.content().strip());
                }
                Room room = requireRoom(roomId);
                UserAccount sender = users.findById(principal.id())
                        .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "invalid_token", "User no longer exists"));
                ChatMessage message = messages.saveAndFlush(new ChatMessage(room, sender, request.content().strip(), request.clientMessageId()));
                MessageView view = MessageView.from(message);
                events.publishEvent(new MessageSaved(view));
                return view;
            });
        } catch (DataIntegrityViolationException ex) {
            // 唯一键冲突后先回滚，再读取另一事务已经提交的消息。
            return transactions.execute(status -> messages.findBySenderIdAndClientMessageId(principal.id(), request.clientMessageId())
                    .map(existing -> checkRetry(existing, roomId, request.content().strip())).orElseThrow(() -> ex));
        }
    }

    private MessageView checkRetry(ChatMessage message, Long roomId, String content) {
        if (!Objects.equals(message.getRoom().getId(), roomId) || !message.getContent().equals(content)) {
            throw new ApiException(HttpStatus.CONFLICT, "idempotency_conflict", "This clientMessageId was used for a different message");
        }
        return MessageView.from(message);
    }

    private Room requireRoom(Long id) {
        return rooms.findById(id).orElseThrow(() -> new ApiException(HttpStatus.NOT_FOUND, "room_not_found", "Room not found"));
    }

    public record HistoryPage(List<MessageView> items, boolean hasMore, Long nextBefore) { }
}
