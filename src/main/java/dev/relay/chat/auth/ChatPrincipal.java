package dev.relay.chat.auth;

import java.time.Instant;

public record ChatPrincipal(long id, String username, Instant expiresAt) { }
