package dev.relay.chat.realtime;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.relay.chat.api.ApiException;
import dev.relay.chat.auth.ChatPrincipal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TicketService {
    private final TicketStore store;
    private final ObjectMapper json;
    private final SecureRandom random = new SecureRandom();

    public TicketService(TicketStore store, ObjectMapper json) {
        this.store = store;
        this.json = json;
    }

    public TicketView issue(ChatPrincipal principal) {
        Instant now = Instant.now();
        if (!principal.expiresAt().isAfter(now)) { throw invalid(); }
        Instant deadline = principal.expiresAt().isBefore(now.plusSeconds(30)) ? principal.expiresAt() : now.plusSeconds(30);
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String ticket = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        try {
            store.put(key(ticket), json.writeValueAsString(new StoredTicket(principal, deadline)), Duration.between(now, deadline));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("Cannot serialize WebSocket ticket", ex);
        }
        return new TicketView(ticket, Math.min(30, Math.max(1, Duration.between(now, deadline).toSeconds())));
    }

    public ChatPrincipal consume(String ticket) {
        if (ticket == null || !ticket.matches("[A-Za-z0-9_-]{43}")) { throw invalid(); }
        String value = store.take(key(ticket));
        if (value == null) { throw invalid(); }
        try {
            StoredTicket stored = json.readValue(value, StoredTicket.class);
            if (!stored.validUntil().isAfter(Instant.now()) || !stored.principal().expiresAt().isAfter(Instant.now())) {
                throw invalid();
            }
            return stored.principal();
        } catch (JsonProcessingException ex) {
            throw invalid();
        }
    }

    private String key(String ticket) {
        try {
            return "relay:ws-ticket:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(ticket.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private ApiException invalid() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "invalid_ticket", "WebSocket ticket is invalid or expired");
    }

    public record TicketView(String ticket, long expiresIn) { }
    public record StoredTicket(ChatPrincipal principal, Instant validUntil) { }
}
