package com.fherrmann.habits.cohabit.store;

import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinsFile;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitsData;
import com.fherrmann.habits.cohabit.model.EventsData;
import com.fherrmann.habits.cohabit.model.Message;
import com.fherrmann.habits.cohabit.model.MessagesFile;
import com.fherrmann.habits.cohabit.model.PeopleData;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.model.PhotosData;
import com.fherrmann.habits.cohabit.model.ReportsData;
import com.fherrmann.habits.cohabit.model.SchedulerState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Alles unter {@code data/cohabit/}: im Speicher gehalten, bei jeder Aenderung
 * atomar geschrieben (erst daneben, dann umbenennen).
 *
 * <p>Ein Lock fuer alles. Bei einer Handvoll Personen ist das keine Bremse,
 * aber es macht jede Aenderung, die mehrere Dateien beruehrt (ein Check-in
 * schreibt Eintrag, Chat und Timeline), zu einer Einheit: niemand liest einen
 * halben Stand.
 *
 * <p>Wer schreibt, bekommt eine {@link Tx}. Jede Datei, auf die er schreibend
 * zugreift, gilt als geaendert und wird am Ende gespeichert. Wirft er, werden
 * genau diese Dateien von der Platte neu gelesen - der Speicher springt auf den
 * letzten gespeicherten Stand zurueck. Was erst nach dem Speichern passieren
 * darf (Push, Dateien loeschen), haengt er mit {@link Tx#afterCommit} an; das
 * laeuft ausserhalb des Locks.
 */
@Component
public class CohabitStore {

    private static final Logger log = LoggerFactory.getLogger(CohabitStore.class);

    /** IDs, die in Dateinamen landen, sind nur aus diesen Zeichen. */
    private static final Pattern SAFE_ID = Pattern.compile("[A-Za-z0-9-]{1,80}");

    private final Path dir;
    private final ObjectMapper mapper;
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock(true);

    private PeopleData people;
    private CohabitsData cohabits;
    private final Map<String, CheckinsFile> checkins = new HashMap<>();
    private final Map<String, MessagesFile> messages = new HashMap<>();
    private EventsData events;
    private ReportsData reports;
    private PhotosData photos;
    private SchedulerState scheduler;

    public CohabitStore(@Value("${cohabit.dir:data/cohabit}") String dir) {
        this.dir = Path.of(dir);
        this.mapper = Json.fileMapper();
        loadAll();
    }

    public Path dir() {
        return dir;
    }

    public Path photosDir() {
        return dir.resolve("photos");
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public static boolean isSafeId(String id) {
        return id != null && SAFE_ID.matcher(id).matches();
    }

    // MARK: - Lesen und Schreiben

    public <T> T read(Function<Data, T> fn) {
        lock.readLock().lock();
        try {
            return fn.apply(new Data());
        } finally {
            lock.readLock().unlock();
        }
    }

    public <T> T write(Function<Tx, T> fn) {
        Tx tx = new Tx();
        T result;
        lock.writeLock().lock();
        try {
            try {
                result = fn.apply(tx);
                tx.persist();
            } catch (RuntimeException | Error e) {
                tx.rollback();
                throw e;
            }
        } finally {
            lock.writeLock().unlock();
        }
        for (Runnable action : tx.afterCommit) {
            try {
                action.run();
            } catch (RuntimeException e) {
                // Ein Push oder eine Aufraeumarbeit darf den gespeicherten Stand nicht in Frage stellen.
                log.warn("Nacharbeit nach dem Speichern fehlgeschlagen", e);
            }
        }
        return result;
    }

    public void update(Consumer<Tx> fn) {
        write(tx -> {
            fn.accept(tx);
            return null;
        });
    }

    // MARK: - Laden

    private void loadAll() {
        people = JsonFiles.read(mapper, file(Kind.PEOPLE, null), PeopleData.class, new PeopleData());
        cohabits = JsonFiles.read(mapper, file(Kind.COHABITS, null), CohabitsData.class, new CohabitsData());
        events = JsonFiles.read(mapper, file(Kind.EVENTS, null), EventsData.class, new EventsData());
        reports = JsonFiles.read(mapper, file(Kind.REPORTS, null), ReportsData.class, new ReportsData());
        photos = JsonFiles.read(mapper, file(Kind.PHOTOS, null), PhotosData.class, new PhotosData());
        scheduler = JsonFiles.read(mapper, file(Kind.SCHEDULER, null), SchedulerState.class, new SchedulerState());
        checkins.clear();
        messages.clear();
        for (String id : idsIn("checkins")) {
            checkins.put(id, JsonFiles.read(mapper, file(Kind.CHECKINS, id), CheckinsFile.class, new CheckinsFile()));
        }
        for (String id : idsIn("messages")) {
            messages.put(id, JsonFiles.read(mapper, file(Kind.MESSAGES, id), MessagesFile.class, new MessagesFile()));
        }
    }

    private List<String> idsIn(String sub) {
        Path folder = dir.resolve(sub);
        if (!Files.isDirectory(folder)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(folder)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(n -> n.endsWith(".json"))
                    .map(n -> n.substring(0, n.length() - ".json".length()))
                    .filter(CohabitStore::isSafeId)
                    .toList();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not list " + folder, e);
        }
    }

    private enum Kind {
        PEOPLE, COHABITS, CHECKINS, MESSAGES, EVENTS, REPORTS, PHOTOS, SCHEDULER
    }

    private record FileRef(Kind kind, String id) {
    }

    private Path file(Kind kind, String id) {
        return switch (kind) {
            case PEOPLE -> dir.resolve("people.json");
            case COHABITS -> dir.resolve("cohabits.json");
            case CHECKINS -> dir.resolve("checkins").resolve(requireSafe(id) + ".json");
            case MESSAGES -> dir.resolve("messages").resolve(requireSafe(id) + ".json");
            case EVENTS -> dir.resolve("events.json");
            case REPORTS -> dir.resolve("reports.json");
            case PHOTOS -> dir.resolve("photos.json");
            case SCHEDULER -> dir.resolve("scheduler.json");
        };
    }

    private static String requireSafe(String id) {
        if (!isSafeId(id)) {
            throw new IllegalArgumentException("Unsafe id: " + id);
        }
        return id;
    }

    // MARK: - Sichten

    /** Lesender Zugriff. Was hier herauskommt, nur innerhalb von read/write benutzen. */
    public class Data {

        public PeopleData people() {
            return people;
        }

        public CohabitsData cohabits() {
            return cohabits;
        }

        public List<Checkin> checkins(String cohabitId) {
            CheckinsFile f = checkins.get(cohabitId);
            return f == null ? List.of() : f.checkins;
        }

        public MessagesFile messagesOf(String cohabitId) {
            MessagesFile f = messages.get(cohabitId);
            return f == null ? new MessagesFile() : f;
        }

        public List<Message> messages(String cohabitId) {
            return messagesOf(cohabitId).messages;
        }

        public EventsData events() {
            return events;
        }

        public ReportsData reports() {
            return reports;
        }

        public PhotosData photos() {
            return photos;
        }

        public SchedulerState scheduler() {
            return scheduler;
        }

        public Optional<Person> person(String id) {
            if (id == null) {
                return Optional.empty();
            }
            return people.persons.stream().filter(p -> p.id.equals(id)).findFirst();
        }

        public Optional<Cohabit> cohabit(String id) {
            if (id == null) {
                return Optional.empty();
            }
            return cohabits.cohabits.stream().filter(c -> c.id.equals(id)).findFirst();
        }
    }

    /** Schreibender Zugriff: jede angefasste Datei wird am Ende gespeichert. */
    public final class Tx extends Data {

        private final Set<FileRef> dirty = new LinkedHashSet<>();
        private final Set<FileRef> deleted = new LinkedHashSet<>();
        private final List<Runnable> afterCommit = new ArrayList<>();

        public PeopleData peopleW() {
            dirty.add(new FileRef(Kind.PEOPLE, null));
            return people;
        }

        public CohabitsData cohabitsW() {
            dirty.add(new FileRef(Kind.COHABITS, null));
            return cohabits;
        }

        public List<Checkin> checkinsW(String cohabitId) {
            FileRef ref = new FileRef(Kind.CHECKINS, requireSafe(cohabitId));
            dirty.add(ref);
            deleted.remove(ref);
            return checkins.computeIfAbsent(cohabitId, k -> new CheckinsFile()).checkins;
        }

        public MessagesFile messagesW(String cohabitId) {
            FileRef ref = new FileRef(Kind.MESSAGES, requireSafe(cohabitId));
            dirty.add(ref);
            deleted.remove(ref);
            return messages.computeIfAbsent(cohabitId, k -> new MessagesFile());
        }

        public EventsData eventsW() {
            dirty.add(new FileRef(Kind.EVENTS, null));
            return events;
        }

        public ReportsData reportsW() {
            dirty.add(new FileRef(Kind.REPORTS, null));
            return reports;
        }

        public PhotosData photosW() {
            dirty.add(new FileRef(Kind.PHOTOS, null));
            return photos;
        }

        public SchedulerState schedulerW() {
            dirty.add(new FileRef(Kind.SCHEDULER, null));
            return scheduler;
        }

        /** Entfernt Eintraege und Chat eines Co-Habits samt ihrer Dateien. */
        public void dropCohabitFiles(String cohabitId) {
            for (Kind kind : List.of(Kind.CHECKINS, Kind.MESSAGES)) {
                FileRef ref = new FileRef(kind, requireSafe(cohabitId));
                dirty.remove(ref);
                deleted.add(ref);
            }
            checkins.remove(cohabitId);
            messages.remove(cohabitId);
        }

        public void afterCommit(Runnable action) {
            afterCommit.add(action);
        }

        private void persist() {
            for (FileRef ref : dirty) {
                JsonFiles.write(mapper, file(ref.kind(), ref.id()), contentOf(ref));
            }
            for (FileRef ref : deleted) {
                JsonFiles.delete(file(ref.kind(), ref.id()));
            }
        }

        private Object contentOf(FileRef ref) {
            return switch (ref.kind()) {
                case PEOPLE -> people;
                case COHABITS -> cohabits;
                case CHECKINS -> checkins.computeIfAbsent(ref.id(), k -> new CheckinsFile());
                case MESSAGES -> messages.computeIfAbsent(ref.id(), k -> new MessagesFile());
                case EVENTS -> events;
                case REPORTS -> reports;
                case PHOTOS -> photos;
                case SCHEDULER -> scheduler;
            };
        }

        /** Zurueck auf den Stand der Platte - fuer alles, was diese Transaktion angefasst hat. */
        private void rollback() {
            Set<FileRef> touched = new LinkedHashSet<>(dirty);
            touched.addAll(deleted);
            for (FileRef ref : touched) {
                Path f = file(ref.kind(), ref.id());
                switch (ref.kind()) {
                    case PEOPLE -> people = JsonFiles.read(mapper, f, PeopleData.class, new PeopleData());
                    case COHABITS -> cohabits = JsonFiles.read(mapper, f, CohabitsData.class, new CohabitsData());
                    case EVENTS -> events = JsonFiles.read(mapper, f, EventsData.class, new EventsData());
                    case REPORTS -> reports = JsonFiles.read(mapper, f, ReportsData.class, new ReportsData());
                    case PHOTOS -> photos = JsonFiles.read(mapper, f, PhotosData.class, new PhotosData());
                    case SCHEDULER -> scheduler = JsonFiles.read(mapper, f, SchedulerState.class, new SchedulerState());
                    case CHECKINS -> {
                        if (Files.exists(f)) {
                            checkins.put(ref.id(), JsonFiles.read(mapper, f, CheckinsFile.class, new CheckinsFile()));
                        } else {
                            checkins.remove(ref.id());
                        }
                    }
                    case MESSAGES -> {
                        if (Files.exists(f)) {
                            messages.put(ref.id(), JsonFiles.read(mapper, f, MessagesFile.class, new MessagesFile()));
                        } else {
                            messages.remove(ref.id());
                        }
                    }
                }
            }
        }
    }
}
