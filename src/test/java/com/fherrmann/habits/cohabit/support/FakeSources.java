package com.fherrmann.habits.cohabit.support;

import com.fherrmann.habits.client.FoodClient;
import com.fherrmann.habits.client.GymClient;
import com.fherrmann.habits.client.LogbookClient;
import com.fherrmann.habits.client.SourceUnavailableException;
import com.fherrmann.habits.client.StepsClient;
import com.fherrmann.habits.security.HealthUsers;
import tools.jackson.databind.json.JsonMapper;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;

/** Kalorienzaehler, Weight Tracker und Logbook ohne Netz - fuer die Tests. */
public final class FakeSources {

    private FakeSources() {
    }

    /** Das Gym im Weight Tracker: je Person die Tage mit beendetem Training. */
    public static final class Gym extends GymClient {
        /** Je Person ({@code "felix"}) Tag → Zahl der beendeten Trainings. */
        public final Map<String, Map<LocalDate, Integer>> trained = new ConcurrentHashMap<>();
        public final AtomicInteger calls = new AtomicInteger();
        /** Der Weight Tracker antwortet nicht. */
        public volatile boolean unavailable;

        public Gym(HealthUsers users) {
            super(JsonMapper.builder().build(), "http://127.0.0.1:9", "test-weight", users);
        }

        public void reset() {
            trained.clear();
            calls.set(0);
            unavailable = false;
        }

        public void train(String personId, LocalDate day) {
            trained.computeIfAbsent(personId, k -> new ConcurrentHashMap<>()).merge(day, 1, Integer::sum);
        }

        @Override
        public Map<LocalDate, Integer> trainingDays(String personId, LocalDate from, LocalDate to,
                                                    java.time.Duration timeout) {
            calls.incrementAndGet();
            if (unavailable) {
                throw new SourceUnavailableException("Gym", 503);
            }
            Map<LocalDate, Integer> result = new HashMap<>();
            trained.getOrDefault(personId, Map.of()).forEach((day, n) -> {
                if (!day.isBefore(from) && !day.isAfter(to)) {
                    result.put(day, n);
                }
            });
            return result;
        }
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

    /**
     * Das Logbook im Weight Tracker: Verhaltensweisen und Werte je Person, mit denselben
     * Ablehnungen wie dort (unbekannt 404, archiviert und zu gross 400).
     */
    public static final class Logbook extends LogbookClient {
        public final Map<String, List<Behavior>> behaviors = new ConcurrentHashMap<>();
        /** Je Person und Verhalten ({@code "felix/b-a"}) die Werte je Tag. */
        public final Map<String, Map<LocalDate, Double>> values = new ConcurrentHashMap<>();
        public final AtomicInteger writes = new AtomicInteger();
        /** Das Logbook antwortet nicht. */
        public volatile boolean unavailable;

        public Logbook(HealthUsers users) {
            super(JsonMapper.builder().build(), "http://127.0.0.1:9", "test-weight", users);
        }

        public void reset() {
            behaviors.clear();
            values.clear();
            writes.set(0);
            unavailable = false;
        }

        public void behavior(String personId, String id, String name, String unit, boolean archived) {
            List<Behavior> list = new ArrayList<>(behaviors.getOrDefault(personId, List.of()));
            list.removeIf(b -> b.id().equals(id));
            list.add(new Behavior(id, name, unit, archived));
            behaviors.put(personId, list);
        }

        public void value(String personId, String behaviorId, LocalDate day, double value) {
            values.computeIfAbsent(personId + "/" + behaviorId, k -> new ConcurrentHashMap<>()).put(day, value);
        }

        public Double valueOf(String personId, String behaviorId, LocalDate day) {
            return values.getOrDefault(personId + "/" + behaviorId, Map.of()).get(day);
        }

        private void reachable() {
            if (unavailable) {
                throw new SourceUnavailableException("Logbook", 503);
            }
        }

        @Override
        public List<Behavior> behaviors(String personId) {
            reachable();
            return List.copyOf(behaviors.getOrDefault(personId, List.of()));
        }

        @Override
        public Map<LocalDate, Double> values(String personId, String behaviorId, LocalDate from, LocalDate to) {
            reachable();
            Map<LocalDate, Double> result = new HashMap<>();
            values.getOrDefault(personId + "/" + behaviorId, Map.of()).forEach((day, value) -> {
                if (!day.isBefore(from) && !day.isAfter(to)) {
                    result.put(day, value);
                }
            });
            return result;
        }

        @Override
        public void setValue(String personId, String behaviorId, LocalDate date, double value) {
            reachable();
            Behavior b = behaviors.getOrDefault(personId, List.of()).stream()
                    .filter(x -> x.id().equals(behaviorId)).findFirst()
                    .orElseThrow(() -> new Rejected(404, "Unbekannte Verhaltensweise."));
            if (b.archived()) {
                throw new Rejected(400, "Die Verhaltensweise ist archiviert.");
            }
            if (value > 10_000) {
                throw new Rejected(400, "Höchstens 10000.");
            }
            writes.incrementAndGet();
            value(personId, behaviorId, date, value);
        }
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
