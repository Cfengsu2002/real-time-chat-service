package dev.relay.chat.message;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.auth.JwtService;
import dev.relay.chat.auth.UserAccount;
import dev.relay.chat.auth.UserRepository;
import dev.relay.chat.room.RoomRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:relay_message_test;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DATABASE_TO_LOWER=TRUE"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
@RecordApplicationEvents
class MessageApiTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private MessageRepository messages;
    @Autowired private MessageService service;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private JwtService jwt;
    @Autowired private ApplicationEvents applicationEvents;
    @Autowired private PlatformTransactionManager transactionManager;

    private UserAccount sender;
    private String token;
    private long roomId;

    @BeforeEach
    void prepare() {
        messages.deleteAllInBatch();
        users.deleteAllInBatch();
        sender = users.saveAndFlush(new UserAccount("message_sender", "unused-password-hash"));
        token = jwt.issue(sender).token();
        roomId = rooms.findAllByOrderByIdAsc().get(0).getId();
    }

    @AfterEach
    void clean() {
        messages.deleteAllInBatch();
        users.deleteAllInBatch();
    }

    @Test
    void messagesRequireAuthentication() throws Exception {
        mvc.perform(get(path(roomId))).andExpect(status().isUnauthorized());
        mvc.perform(post(path(roomId)).contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "Hello")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false));
        assertThat(messages.count()).isZero();
    }

    @Test
    void persistsMessageWithSafePublicFieldsAndReadsItAsAnotherUser() throws Exception {
        UUID clientId = UUID.randomUUID();
        String content = "<script>alert('hello')</script>\nHello 中文 👋";
        JsonNode saved = send(roomId, clientId, "  " + content + "  ");
        assertThat(saved.path("id").asLong()).isPositive();
        assertThat(saved.path("roomId").asLong()).isEqualTo(roomId);
        assertThat(saved.path("userId").asLong()).isEqualTo(sender.getId());
        assertThat(saved.path("username").asText()).isEqualTo(sender.getUsername());
        assertThat(saved.path("clientMessageId").asText()).isEqualTo(clientId.toString());
        assertThat(saved.path("content").asText()).isEqualTo(content);
        assertThat(saved.path("createdAt").asText()).isNotBlank();
        assertThat(saved.has("passwordHash")).isFalse();
        assertThat(messages.findById(saved.path("id").asLong()).orElseThrow().getContent()).isEqualTo(content);

        UserAccount other = users.saveAndFlush(new UserAccount("other_reader", "unused-password-hash"));
        mvc.perform(get(path(roomId)).header("Authorization", "Bearer " + jwt.issue(other).token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.messages", hasSize(1)))
                .andExpect(jsonPath("$.data.messages[0].content").value(content))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void returnsEmptyHistoryForRoomWithoutMessages() throws Exception {
        mvc.perform(get(path(roomId)).header("Authorization", bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.messages", hasSize(0)))
                .andExpect(jsonPath("$.data.hasMore").value(false));
    }

    @Test
    void latestAndBeforePagesAreAscendingAndDoNotOverlap() throws Exception {
        List<Long> ids = sendSequence(5);
        JsonNode latest = history("?limit=2");
        assertThat(messageIds(latest)).containsExactly(ids.get(3), ids.get(4));
        assertThat(latest.path("hasMore").asBoolean()).isTrue();
        JsonNode older = history("?limit=2&before=" + ids.get(3));
        assertThat(messageIds(older)).containsExactly(ids.get(1), ids.get(2));
        assertThat(older.path("hasMore").asBoolean()).isTrue();
        JsonNode oldest = history("?limit=2&before=" + ids.get(1));
        assertThat(messageIds(oldest)).containsExactly(ids.get(0));
        assertThat(oldest.path("hasMore").asBoolean()).isFalse();
    }

    @Test
    void afterCursorPagesRecoverAllMissingMessagesInAscendingOrder() throws Exception {
        List<Long> ids = sendSequence(5);
        assertThat(messageIds(history("?limit=2&after=0"))).containsExactly(ids.get(0), ids.get(1));
        JsonNode first = history("?limit=2&after=" + ids.get(0));
        assertThat(messageIds(first)).containsExactly(ids.get(1), ids.get(2));
        assertThat(first.path("hasMore").asBoolean()).isTrue();
        JsonNode second = history("?limit=2&after=" + ids.get(2));
        assertThat(messageIds(second)).containsExactly(ids.get(3), ids.get(4));
        assertThat(second.path("hasMore").asBoolean()).isFalse();
        assertThat(messageIds(history("?after=" + ids.get(4)))).isEmpty();
    }

    @Test
    void historyIsScopedToTheRequestedRoom() throws Exception {
        long secondRoom = rooms.findAllByOrderByIdAsc().get(1).getId();
        JsonNode first = send(roomId, UUID.randomUUID(), "First room");
        send(secondRoom, UUID.randomUUID(), "Second room");
        assertThat(messageIds(history(""))).containsExactly(first.path("id").asLong());
    }

    @Test
    void matchingRetryKeepsOneRowAndPublishesOnlyOneCreationEvent() throws Exception {
        UUID clientId = UUID.randomUUID();
        JsonNode first = send(roomId, clientId, "hello");
        JsonNode retry = send(roomId, clientId, "  hello  ");
        assertThat(retry).isEqualTo(first);
        assertThat(messages.count()).isEqualTo(1);
        assertThat(applicationEvents.stream(MessageCreatedEvent.class).count()).isEqualTo(1);
    }

    @Test
    void reusedIdWithDifferentRoomOrContentIsRejected() throws Exception {
        UUID clientId = UUID.randomUUID();
        send(roomId, clientId, "original");
        mvc.perform(post(path(roomId)).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(request(clientId, "changed")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("message_id_conflict"));
        long secondRoom = rooms.findAllByOrderByIdAsc().get(1).getId();
        mvc.perform(post(path(secondRoom)).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(request(clientId, "original")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("message_id_conflict"));
        assertThat(messages.count()).isEqualTo(1);
    }

    @Test
    void clientIdsAreScopedToSender() throws Exception {
        UUID clientId = UUID.randomUUID();
        send(roomId, clientId, "same id, first sender");
        UserAccount other = users.saveAndFlush(new UserAccount("other_sender", "unused-password-hash"));
        mvc.perform(post(path(roomId)).header("Authorization", "Bearer " + jwt.issue(other).token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(request(clientId, "same id, second sender")))
                .andExpect(status().isCreated());
        assertThat(messages.count()).isEqualTo(2);
    }

    @Test
    void concurrentRetryCreatesOneMessage() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        ChatPrincipal principal = new ChatPrincipal(sender.getId(), sender.getUsername(), Instant.now().plusSeconds(60));
        SendMessageRequest request = new SendMessageRequest(UUID.randomUUID(), "concurrent retry");
        Callable<MessageView> task = () -> {
            assertThat(start.await(5, TimeUnit.SECONDS)).isTrue();
            return service.send(roomId, principal, request);
        };
        try {
            Future<MessageView> first = executor.submit(task);
            Future<MessageView> second = executor.submit(task);
            start.countDown();
            assertThat(first.get(10, TimeUnit.SECONDS).id()).isEqualTo(second.get(10, TimeUnit.SECONDS).id());
            assertThat(messages.count()).isEqualTo(1);
        } finally {
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void roomLockKeepsCursorOrderAlignedWithCommitOrder() throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch firstSaved = new CountDownLatch(1);
        CountDownLatch allowFirstCommit = new CountDownLatch(1);
        CountDownLatch secondStarted = new CountDownLatch(1);
        ChatPrincipal principal = new ChatPrincipal(sender.getId(), sender.getUsername(), Instant.now().plusSeconds(60));
        TransactionTemplate outerTransaction = new TransactionTemplate(transactionManager);
        try {
            Future<MessageView> first = executor.submit(() -> outerTransaction.execute(status -> {
                MessageView message = service.send(roomId, principal,
                        new SendMessageRequest(UUID.randomUUID(), "first committed message"));
                firstSaved.countDown();
                try {
                    if (!allowFirstCommit.await(5, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("First commit was not released");
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
                return message;
            }));
            assertThat(firstSaved.await(5, TimeUnit.SECONDS)).isTrue();
            Future<MessageView> second = executor.submit(() -> {
                secondStarted.countDown();
                return service.send(roomId, principal,
                        new SendMessageRequest(UUID.randomUUID(), "second committed message"));
            });
            assertThat(secondStarted.await(5, TimeUnit.SECONDS)).isTrue();
            assertThatThrownBy(() -> second.get(250, TimeUnit.MILLISECONDS)).isInstanceOf(TimeoutException.class);
            allowFirstCommit.countDown();
            MessageView firstResult = first.get(5, TimeUnit.SECONDS);
            MessageView secondResult = second.get(5, TimeUnit.SECONDS);
            assertThat(secondResult.id()).isGreaterThan(firstResult.id());
            assertThat(service.history(roomId, null, firstResult.id(), 50).messages())
                    .extracting(MessageView::id).containsExactly(secondResult.id());
        } finally {
            allowFirstCommit.countDown();
            executor.shutdownNow();
            assertThat(executor.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void validatesContentAndClientId() throws Exception {
        for (String invalid : List.of("", "  \n\t  ", "x".repeat(2001), "hello\u0000world")) {
            mvc.perform(post(path(roomId)).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content(request(UUID.randomUUID(), invalid)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("validation_failed"));
        }
        for (String invalid : List.of("{}", "{\"clientMessageId\":\"not-a-uuid\",\"content\":\"Hello\"}",
                "{\"content\":\"Hello\"}", "{\"clientMessageId\":null,\"content\":\"Hello\"}",
                "{\"clientMessageId\":\"" + UUID.randomUUID() + "\",\"content\":\"Hello\",\"userId\":99}")) {
            mvc.perform(post(path(roomId)).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                            .content(invalid))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        assertThat(messages.count()).isZero();
        send(roomId, UUID.randomUUID(), "x".repeat(2000));
    }

    @Test
    void rejectsInvalidPaginationAndMissingRooms() throws Exception {
        for (String query : List.of("?before=1&after=2", "?before=0", "?after=-1", "?limit=0", "?limit=101",
                "?limit=abc", "?before=abc", "?after=9223372036854775808")) {
            mvc.perform(get(path(roomId) + query).header("Authorization", bearer()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false));
        }
        mvc.perform(get(path(Long.MAX_VALUE)).header("Authorization", bearer()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("room_not_found"));
        mvc.perform(post(path(Long.MAX_VALUE)).header("Authorization", bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content(request(UUID.randomUUID(), "Hello")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("room_not_found"));
    }

    private JsonNode send(long room, UUID clientId, String content) throws Exception {
        String body = mvc.perform(post(path(room)).header("Authorization", bearer())
                        .contentType(MediaType.APPLICATION_JSON).content(request(clientId, content)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private JsonNode history(String query) throws Exception {
        String body = mvc.perform(get(path(roomId) + query).header("Authorization", bearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private List<Long> sendSequence(int count) throws Exception {
        List<Long> ids = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ids.add(send(roomId, UUID.randomUUID(), "Message " + i).path("id").asLong());
        }
        return ids;
    }

    private List<Long> messageIds(JsonNode page) {
        List<Long> ids = new ArrayList<>();
        page.path("messages").forEach(message -> ids.add(message.path("id").asLong()));
        return ids;
    }

    private String request(UUID clientId, String content) throws Exception {
        return json.writeValueAsString(Map.of("clientMessageId", clientId.toString(), "content", content));
    }

    private String path(long room) {
        return "/api/rooms/" + room + "/messages";
    }

    private String bearer() {
        return "Bearer " + token;
    }
}
