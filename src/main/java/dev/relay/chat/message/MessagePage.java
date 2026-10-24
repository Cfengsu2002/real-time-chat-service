package dev.relay.chat.message;

import java.util.List;

public record MessagePage(List<MessageView> messages, boolean hasMore) { }
