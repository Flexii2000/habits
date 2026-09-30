package com.fherrmann.habits.cohabit.api;

import java.util.List;

public record TimelinePage(List<TimelineItem> items, boolean hasMore) {
}
