package dev.relay.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.auth.ChatPrincipal;
import dev.relay.chat.auth.UserRepository;
import dev.relay.chat.chat.ChatService;
import dev.relay.chat.chat.MessageRepository;
import dev.relay.chat.chat.MessageView;
import dev.relay.chat.chat.SendMessageRequest;
import dev.relay.chat.realtime.MessageBroker;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ApiIntegrationTest {
    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private MessageRepository messages;
    @Autowired private UserRepository users;
    @Autowired private ChatService chat;
    @MockitoBean private MessageBroker broker;

    @BeforeEach
    void clearUsersAndMessages() {
        messages.deleteAll();
        users.deleteAll();
    }

    @Test
    void registrationLoginAndProtectedRoutesUseStandardResponses() throws Exception {
        mvc.perform(get("/api/rooms")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false)).andExpect(jsonPath("$.code").value("unauthorized"));
        JsonNode auth = register("Alice");
        String token = auth.path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(auth.path("expiresAt").asText()).isNotBlank();
        assertThat(auth.path("user").path("username").asText()).isEqualTo("alice");
        assertThat(users.findByUsername("alice").orElseThrow().getPasswordHash()).startsWith("$2").doesNotContain("password123");
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.username").value("alice"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", "ALICE", "password", "password123"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.user.username").value("alice"));
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", "alice", "password", "wrongpass"))))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_credentials"));
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer corrupt-token"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("invalid_token"));
    }

    @Test
    void validatesCredentialsAndUniqueUsername() throws Exception {
        register("member");
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", "MEMBER", "password", "password123"))))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", "a!", "password", "short"))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", "unicode", "password", "密".repeat(25)))))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("validation_failed"));
    }

    @Test
    void historyPaginatesByIdAndRetriesAreIdempotent() throws Exception {
        JsonNode auth = register("history");
        String token = auth.path("token").asText();
        UUID key = UUID.randomUUID();
        JsonNode first = send(token, 1, "First message", key);
        JsonNode retry = send(token, 1, "First message", key);
        assertThat(retry.path("id").asLong()).isEqualTo(first.path("id").asLong());
        JsonNode second = send(token, 1, "Second message", UUID.randomUUID());
        JsonNode third = send(token, 1, "Third message", UUID.randomUUID());
        assertThat(messages.count()).isEqualTo(3);
        verify(broker, times(3)).publish(any(MessageView.class));
        JsonNode page = data(mvc.perform(get("/api/rooms/1/messages").param("limit", "2")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        assertThat(page.path("hasMore").asBoolean()).isTrue();
        assertThat(page.path("items").get(0).path("id").asLong()).isEqualTo(second.path("id").asLong());
        assertThat(page.path("items").get(1).path("id").asLong()).isEqualTo(third.path("id").asLong());
        assertThat(page.path("nextBefore").asLong()).isEqualTo(second.path("id").asLong());
        mvc.perform(get("/api/rooms/1/messages").param("before", page.path("nextBefore").asText()).param("limit", "2")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.items.length()").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(first.path("id").asLong()))
                .andExpect(jsonPath("$.data.hasMore").value(false));
        mvc.perform(post("/api/rooms/1/messages").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("content", "Changed content", "clientMessageId", key))))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("idempotency_conflict"));
        mvc.perform(post("/api/rooms/2/messages").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("content", "First message", "clientMessageId", key))))
                .andExpect(status().isConflict());
    }

    @Test
    void concurrentRetriesCreateOneCommittedMessage() throws Exception {
        JsonNode auth = register("concurrent");
        ChatPrincipal principal = new ChatPrincipal(auth.path("user").path("id").asLong(), "concurrent", Instant.now().plusSeconds(3600));
        SendMessageRequest request = new SendMessageRequest("Concurrent retry", UUID.randomUUID());
        ExecutorService executor = Executors.newFixedThreadPool(4);
        try {
            List<Callable<MessageView>> tasks = List.of(
                    () -> chat.send(1L, principal, request), () -> chat.send(1L, principal, request),
                    () -> chat.send(1L, principal, request), () -> chat.send(1L, principal, request));
            List<java.util.concurrent.Future<MessageView>> results = executor.invokeAll(tasks);
            Long id = results.get(0).get().id();
            for (var result : results) { assertThat(result.get().id()).isEqualTo(id); }
            assertThat(messages.count()).isEqualTo(1);
            verify(broker, times(1)).publish(any(MessageView.class));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void roomsAndMessagesValidateInputAndRoomExistence() throws Exception {
        String token = register("validation").path("token").asText();
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].name").value("general"));
        mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("name", "random-" + UUID.randomUUID().toString().substring(0, 8), "description", "Test room"))))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.success").value(true));
        mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("name", "bad--slug"))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rooms/1/messages").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("content", " ", "clientMessageId", UUID.randomUUID()))))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/rooms/1/messages").header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("content", "x".repeat(2001), "clientMessageId", UUID.randomUUID()))))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/rooms/999999/messages").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/rooms/1/messages").param("limit", "101").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/rooms/1/messages").param("before", "0").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void websocketTicketEndpointRequiresAuthentication() throws Exception {
        mvc.perform(post("/api/auth/ws-ticket")).andExpect(status().isUnauthorized());
        String token = register("tickets").path("token").asText();
        mvc.perform(post("/api/auth/ws-ticket").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.expiresIn").value(30))
                .andExpect(jsonPath("$.data.ticket").isString());
    }

    private JsonNode register(String username) throws Exception {
        return data(mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsBytes(Map.of("username", username, "password", "password123"))))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode send(String token, long room, String content, UUID key) throws Exception {
        return data(mvc.perform(post("/api/rooms/" + room + "/messages").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsBytes(Map.of("content", content, "clientMessageId", key))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private JsonNode data(String response) throws Exception { return json.readTree(response).path("data"); }
}
