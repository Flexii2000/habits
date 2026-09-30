package com.fherrmann.habits.cohabit.api;

import java.util.List;

public record MessagePage(List<MessageView> messages, boolean hasMore) {
}
