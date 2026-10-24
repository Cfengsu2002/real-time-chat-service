package dev.relay.chat.message;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SendMessageRequest(
        @NotNull UUID clientMessageId,
        @NotBlank @Size(max = 2000) @Pattern(regexp = "[^\\x00]*", message = "must not contain null characters")
        String content) { }
