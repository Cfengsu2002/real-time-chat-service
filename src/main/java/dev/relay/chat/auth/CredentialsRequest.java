package dev.relay.chat.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CredentialsRequest(
        @NotBlank @Size(min = 3, max = 24)
        @Pattern(regexp = "[A-Za-z0-9_]+", message = "Use letters, numbers, and underscores") String username,
        @NotBlank @Size(min = 8, max = 72) String password) { }
