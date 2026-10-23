package dev.relay.chat.auth;

import java.time.Instant;

public record ChatPrincipal(Long id, String username, Instant expiresAt) { }
