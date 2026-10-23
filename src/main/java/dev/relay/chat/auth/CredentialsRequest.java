package dev.relay.chat.auth;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record CredentialsRequest(
        @NotBlank @Pattern(regexp = "[a-zA-Z0-9_]{3,24}", message = "Use 3–24 letters, numbers, or underscores") String username,
        @NotBlank @Size(min = 8, max = 72, message = "Use 8–72 characters (at most 72 UTF-8 bytes)") String password) { }
