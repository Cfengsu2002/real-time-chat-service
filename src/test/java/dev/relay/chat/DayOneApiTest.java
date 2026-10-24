package dev.relay.chat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import dev.relay.chat.auth.UserAccount;
import dev.relay.chat.auth.UserRepository;
import dev.relay.chat.room.RoomRepository;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItems;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DayOneApiTest {
    private static final String PASSWORD = "test-password-42";
    private static final List<String> SEED_ROOMS = List.of("general", "design", "engineering");

    @Autowired private MockMvc mvc;
    @Autowired private ObjectMapper json;
    @Autowired private UserRepository users;
    @Autowired private RoomRepository rooms;
    @Autowired private PasswordEncoder passwords;
    @Value("${app.jwt.secret}") private String secret;

    @BeforeEach
    void resetTestData() {
        users.deleteAll();
        // 保留迁移创建的三个房间，只清除各测试新增的数据。
        rooms.deleteAll(rooms.findAll().stream().filter(room -> !SEED_ROOMS.contains(room.getName())).toList());
    }

    @Test
    void registrationLoginAndMeUseStandardResponses() throws Exception {
        JsonNode registered = register("Alex", PASSWORD);
        String token = registered.path("token").asText();
        assertThat(token).isNotBlank();
        assertThat(registered.path("expiresAt").asText()).isNotBlank();
        assertThat(registered.path("user").path("username").asText()).isEqualTo("alex");

        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ALEx", PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.user.username").value("alex"))
                .andExpect(jsonPath("$.data.token").isString());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(registered.path("user").path("id").asLong()))
                .andExpect(jsonPath("$.data.username").value("alex"))
                .andExpect(jsonPath("$.data.passwordHash").doesNotExist());
    }

    @Test
    void passwordsAreStoredAsBcryptWithCostTwelve() throws Exception {
        register("alex", PASSWORD);
        UserAccount stored = users.findByUsername("alex").orElseThrow();
        assertThat(stored.getPasswordHash()).matches("\\$2[aby]\\$12\\$.*");
        assertThat(stored.getPasswordHash()).isNotEqualTo(PASSWORD);
        assertThat(passwords.matches(PASSWORD, stored.getPasswordHash())).isTrue();
    }

    @Test
    void protectedRoutesRejectMissingAndMalformedTokens() throws Exception {
        mvc.perform(get("/api/rooms"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("unauthorized"))
                .andExpect(jsonPath("$.data").doesNotExist());
        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer malformed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("invalid_token"));
        mvc.perform(post("/api/rooms").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"not-public\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void duplicateUsernameAndBadCredentialsAreRejected() throws Exception {
        register("Alex", PASSWORD);
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("ALEX", PASSWORD)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("username_taken"));
        for (String username : List.of("alex", "missing_user")) {
            mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                            .content(credentials(username, "wrong-password")))
                    .andExpect(status().isUnauthorized())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("invalid_credentials"));
        }
    }

    @Test
    void invalidRegistrationInputIsRejected() throws Exception {
        List<Map<String, String>> invalid = List.of(
                Map.of("username", "ab", "password", PASSWORD),
                Map.of("username", "a".repeat(25), "password", PASSWORD),
                Map.of("username", "bad-name", "password", PASSWORD),
                Map.of("username", "alex", "password", "short"),
                Map.of("username", "alex", "password", "密".repeat(25)));
        for (Map<String, String> input : invalid) {
            mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                            .content(json.writeValueAsString(input)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("validation_failed"));
        }
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void exactlySeventyTwoUtf8BytesAreAccepted() throws Exception {
        String password = "密".repeat(24);
        register("alex", password);
        mvc.perform(post("/api/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials("alex", password)))
                .andExpect(status().isOk());
    }

    @Test
    void seedRoomsAreAvailableAndNewRoomsCanBeCreated() throws Exception {
        String token = register("alex", PASSWORD).path("token").asText();
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data", hasSize(3)))
                .andExpect(jsonPath("$.data[*].name", hasItems("general", "design", "engineering")))
                .andExpect(jsonPath("$.data[0].createdAt").isString());
        mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of("name", "release-notes", "description", "  Updates  "))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("release-notes"))
                .andExpect(jsonPath("$.data.description").value("Updates"))
                .andExpect(jsonPath("$.data.createdAt").isString());
        assertThat(rooms.existsByName("release-notes")).isTrue();
    }

    @Test
    void roomValidationAndDuplicateNamesUseStandardErrors() throws Exception {
        String token = register("alex", PASSWORD).path("token").asText();
        mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"general\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("room_exists"));
        List<Map<String, String>> invalid = List.of(
                Map.of("name", "UPPERCASE"),
                Map.of("name", "a--b"),
                Map.of("name", "ab"),
                Map.of("name", "a".repeat(49)),
                Map.of("name", "valid-room", "description", "x".repeat(161)));
        for (Map<String, String> input : invalid) {
            mvc.perform(post("/api/rooms").header("Authorization", "Bearer " + token)
                            .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(input)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.success").value(false))
                    .andExpect(jsonPath("$.code").value("validation_failed"));
        }
    }

    @Test
    void notFoundMethodNotAllowedAndForbiddenUseDtos() throws Exception {
        String token = register("alex", PASSWORD).path("token").asText();
        mvc.perform(get("/api/missing").header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("not_found"));
        mvc.perform(put("/api/rooms").header("Authorization", "Bearer " + token))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("method_not_allowed"));
        mvc.perform(get("/actuator/info").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("forbidden"));
    }

    @Test
    void expiredJwtIsRejectedByTheFilter() throws Exception {
        String expired = signedToken(secret, Instant.now().minusSeconds(1));
        mvc.perform(get("/api/rooms").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.code").value("invalid_token"));
    }

    @Test
    void malformedJsonAndUnknownFieldsAreRejected() throws Exception {
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
        mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"alex\",\"password\":\"test-password-42\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("invalid_request"));
    }

    @Test
    void healthProbesDoNotRequireAuthentication() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    private JsonNode register(String username, String password) throws Exception {
        String body = mvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content(credentials(username, password)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.code").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private String credentials(String username, String password) throws Exception {
        return json.writeValueAsString(Map.of("username", username, "password", password));
    }

    private String signedToken(String key, Instant expiresAt) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader(JWSAlgorithm.HS256), new JWTClaimsSet.Builder()
                .issuer("relay-chat").subject("1").claim("username", "alex")
                .expirationTime(Date.from(expiresAt)).build());
        token.sign(new MACSigner(key));
        return token.serialize();
    }
}
