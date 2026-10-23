package dev.relay.chat.chat;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.UUID;

public record SendMessageRequest(@NotBlank @Size(max = 2000) String content, @NotNull UUID clientMessageId) { }
