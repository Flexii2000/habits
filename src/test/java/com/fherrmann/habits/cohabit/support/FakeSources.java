package com.fherrmann.habits.cohabit.support;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.StepsClient;
import com.fherrmann.habits.security.HealthUsers;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Kalorienzaehler und Weight Tracker ohne Netz - fuer die Tests. */
public final class FakeSources {

    private FakeSources() {
    }

    public static final class Food extends FoodClient {
        public volatile BiFunction<String, LocalDate, Day> days = (person, day) -> new Day(0, 0, java.util.Set.of());
        public final AtomicInteger calls = new AtomicInteger();

        public Food(HealthUsers users) {
            super(JsonMapper.builder().build(), "http://127.0.0.1:9", "test-private", users);
        }

        @Override
        public Day day(String personId, LocalDate date) {
            calls.incrementAndGet();
            return days.apply(personId, date);
        }

        /** Wie der echte Bereichsaufruf: jeder Tag des Zeitraums, aus derselben Tabelle wie {@link #day}. */
        @Override
        public Map<LocalDate, Day> range(String personId, LocalDate from, LocalDate to) {
            calls.incrementAndGet();
            if (unavailable) {
                throw new com.fherrmann.habits.client.SourceUnavailableException("Kalorienzähler", 503);
            }
            Map<LocalDate, Day> result = new HashMap<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                result.put(d, days.apply(personId, d));
            }
            return result;
        }

        /** Der Kalorienzaehler antwortet nicht - nur fuer den Bereichsaufruf. */
        public volatile boolean unavailable;
    }

    public static final class Steps extends StepsClient {
        public volatile BiFunction<String, LocalDate, Integer> perDay = (person, day) -> 0;

        public Steps(HealthUsers users) {
            super(JsonMapper.builder().build(), "http://127.0.0.1:9", "test-weight", users);
        }

        @Override
        public Map<LocalDate, Integer> steps(String personId, LocalDate from, LocalDate to) {
            Map<LocalDate, Integer> result = new HashMap<>();
            for (LocalDate d = from; !d.isAfter(to); d = d.plusDays(1)) {
                int value = perDay.apply(personId, d);
                if (value > 0) {
                    result.put(d, value);
                }
            }
            return result;
        }
    }
}
