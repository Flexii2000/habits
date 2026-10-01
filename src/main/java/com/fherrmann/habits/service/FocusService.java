package com.fherrmann.habits.service;

import com.fherrmann.habits.dto.FocusCategoryView;
import com.fherrmann.habits.dto.FocusSessionRequest;
import com.fherrmann.habits.dto.FocusSessionView;
import com.fherrmann.habits.model.FocusCategory;
import com.fherrmann.habits.model.FocusData;
import com.fherrmann.habits.model.FocusSession;
import com.fherrmann.habits.repository.FocusRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Die Fokus-Sessions: annehmen, auflisten, je Tag summieren - und ihre Kategorien.
 *
 * <p>Eine Session gehoert zu dem Tag, an dem sie <em>begann</em> (in
 * {@code habits.zone}). Wer um 23:30 pflanzt und um 0:30 fertig ist, hat
 * am Abend fokussiert - nicht am naechsten Morgen.
 *
 * <p>Kategorien (seit 01.10.2026): vor dem Pflanzen gewaehlt, von Felix benannt.
 * coHabit kann ein Fokus-Habit auf eine Kategorie beschraenken ("1 h Bachelorarbeit
 * am Tag"). Geloescht wird nur markiert, damit alte Baeume ihren Namen behalten.
 */
@Service
public class FocusService {

    /**
     * Kuerzer geht keine Session. Die 30 Minuten, unter denen die App keinen
     * Baum anbietet, sind ihre Regel, nicht die des Dienstes: zum Testen
     * pflanzt sie auch einen Baum von einer Minute.
     */
    static final int MIN_MINUTES = 1;
    /** Laenger als einen Tag ist keine Session, sondern ein Fehler im Client. */
    static final int MAX_MINUTES = 24 * 60;
    /** So viel darf das Ende in der Zukunft liegen - Uhren gehen nie ganz gleich. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);
    static final int MAX_CATEGORY_NAME = 40;
    static final Pattern CLIENT_ID = Pattern.compile("[A-Za-z0-9-]{8,64}");

    /** Eine Kategorie hat einen neuen Namen - coHabit zieht ihn in seinen Fokus-Habits nach. */
    public record CategoryRenamed(String id, String name) {
    }

    private final FocusRepository repository;
    private final Clock clock;
    private final ApplicationEventPublisher events;

    @Autowired
    public FocusService(FocusRepository repository, Clock clock, ApplicationEventPublisher events) {
        this.repository = repository;
        this.clock = clock;
        this.events = events;
    }

    /** Ohne Spring (Tests, Migration): Umbenennungen meldet dann niemand weiter. */
    public FocusService(FocusRepository repository, Clock clock) {
        this(repository, clock, event -> { });
    }

    /**
     * Nimmt eine abgeschlossene Session an. Dieselbe Id noch einmal - etwa
     * aus dem Postausgang der App nachgesendet - aendert nichts und liefert
     * den vorhandenen Baum.
     *
     * @return die Session und ob sie neu war
     */
    public synchronized Recorded record(FocusSessionRequest request) {
        if (request == null || request.id() == null || request.id().isBlank()) {
            throw badRequest("Eine Session braucht eine Id.");
        }
        if (request.start() == null || request.end() == null) {
            throw badRequest("Eine Session braucht Anfang und Ende.");
        }
        FocusData data = repository.load();
        Optional<FocusSession> existing = data.sessions().stream()
                .filter(s -> s.id().equals(request.id().trim()))
                .findFirst();
        if (existing.isPresent()) {
            return new Recorded(view(data, existing.get()), false);
        }
        long minutes = Duration.between(request.start(), request.end()).toMinutes();
        if (minutes < MIN_MINUTES) {
            throw badRequest("Eine Session dauert mindestens eine Minute.");
        }
        if (minutes > MAX_MINUTES) {
            throw badRequest("Eine Session dauert höchstens einen Tag.");
        }
        if (request.end().isAfter(Instant.now(clock).plus(CLOCK_SKEW))) {
            throw badRequest("Die Session ist noch nicht vorbei.");
        }
        String categoryId = blankToNull(request.categoryId());
        // Auch eine inzwischen geloeschte Kategorie gilt: der Baum kann im Postausgang
        // gewartet haben, waehrend sie geloescht wurde.
        if (categoryId != null && find(data, categoryId).isEmpty()) {
            throw badRequest("Unbekannte Kategorie.");
        }
        FocusSession session = new FocusSession(request.id().trim(), request.start(), request.end(), categoryId);
        List<FocusSession> sessions = new ArrayList<>(data.sessions());
        sessions.add(session);
        repository.save(new FocusData(sessions, data.categories()));
        return new Recorded(view(data, session), true);
    }

    /** Nimmt einen Baum wieder weg - etwa einen Testbaum von frueher. 404, wenn es ihn nicht gibt. */
    public synchronized void delete(String id) {
        FocusData data = repository.load();
        List<FocusSession> remaining = data.sessions().stream()
                .filter(s -> !s.id().equals(id))
                .toList();
        if (remaining.size() == data.sessions().size()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Kein Baum mit dieser Id.");
        }
        repository.save(new FocusData(remaining, data.categories()));
    }

    /** Sessions, deren Tag im Zeitraum liegt - neueste zuerst. */
    public List<FocusSessionView> list(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw badRequest("from und to sind Pflicht, und to darf nicht vor from liegen.");
        }
        FocusData data = repository.load();
        return data.sessions().stream()
                .map(s -> view(data, s))
                .filter(v -> !v.day().isBefore(from) && !v.day().isAfter(to))
                .sorted(Comparator.comparing(FocusSessionView::start).reversed())
                .toList();
    }

    /** Fokus-Minuten je Tag im Zeitraum; Tage ohne Session fehlen. */
    public Map<LocalDate, Integer> minutesPerDay(LocalDate from, LocalDate to) {
        return minutesPerDay(from, to, null);
    }

    /**
     * Fokus-Minuten je Tag im Zeitraum, nur Baeume dieser Kategorie ({@code null}: alle).
     * Tage ohne Session fehlen.
     */
    public Map<LocalDate, Integer> minutesPerDay(LocalDate from, LocalDate to, String categoryId) {
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (FocusSession s : repository.load().sessions()) {
            if (categoryId != null && !categoryId.equals(s.categoryId())) {
                continue;
            }
            LocalDate day = dayOf(s);
            if (!day.isBefore(from) && !day.isAfter(to)) {
                perDay.merge(day, s.minutes(), Integer::sum);
            }
        }
        return perDay;
    }

    // MARK: - Kategorien

    /** Die Kategorien zur Auswahl, in der Reihenfolge des Anlegens. */
    public List<FocusCategoryView> categories() {
        return repository.load().categories().stream()
                .filter(c -> !c.deleted())
                .map(c -> new FocusCategoryView(c.id(), c.name()))
                .toList();
    }

    /** Der Name einer Kategorie, auch einer geloeschten; leer, wenn es sie nie gab. */
    public Optional<String> categoryName(String id) {
        return id == null ? Optional.empty() : find(repository.load(), id).map(FocusCategory::name);
    }

    /** Ob eine Kategorie zur Auswahl steht (es sie gibt und sie nicht geloescht ist). */
    public boolean isActiveCategory(String id) {
        return id != null && find(repository.load(), id).map(c -> !c.deleted()).orElse(false);
    }

    /**
     * Legt eine Kategorie an. Dieselbe Id noch einmal (Postausgang) liefert die
     * vorhandene; ein Name, den es schon gibt, ist ein Konflikt.
     *
     * @return die Kategorie und ob sie neu war
     */
    public synchronized CategoryCreated createCategory(String requestedId, String rawName) {
        String name = name(rawName);
        FocusData data = repository.load();
        String id = blankToNull(requestedId);
        if (id != null) {
            if (!CLIENT_ID.matcher(id).matches()) {
                throw badRequest("Die Id der Kategorie ist ungültig.");
            }
            Optional<FocusCategory> existing = find(data, id);
            if (existing.isPresent()) {
                return new CategoryCreated(new FocusCategoryView(id, existing.get().name()), false);
            }
        } else {
            id = UUID.randomUUID().toString();
        }
        requireUnique(data, name, null);
        FocusCategory category = new FocusCategory(id, name, Instant.now(clock).truncatedTo(ChronoUnit.SECONDS), false);
        List<FocusCategory> categories = new ArrayList<>(data.categories());
        categories.add(category);
        repository.save(new FocusData(data.sessions(), categories));
        return new CategoryCreated(new FocusCategoryView(id, name), true);
    }

    public synchronized FocusCategoryView renameCategory(String id, String rawName) {
        String name = name(rawName);
        FocusData data = repository.load();
        FocusCategory category = find(data, id).filter(c -> !c.deleted())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Keine Kategorie mit dieser Id."));
        if (!category.name().equals(name)) {
            requireUnique(data, name, id);
            replace(data, new FocusCategory(id, name, category.createdAt(), false));
            events.publishEvent(new CategoryRenamed(id, name));
        }
        return new FocusCategoryView(id, name);
    }

    /** Nur markiert: Baeume und Co-Habits mit dieser Kategorie behalten ihren Namen. */
    public synchronized void deleteCategory(String id) {
        FocusData data = repository.load();
        FocusCategory category = find(data, id).filter(c -> !c.deleted())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Keine Kategorie mit dieser Id."));
        replace(data, new FocusCategory(id, category.name(), category.createdAt(), true));
    }

    private void replace(FocusData data, FocusCategory changed) {
        List<FocusCategory> categories = data.categories().stream()
                .map(c -> c.id().equals(changed.id()) ? changed : c)
                .toList();
        repository.save(new FocusData(data.sessions(), categories));
    }

    private static Optional<FocusCategory> find(FocusData data, String id) {
        return data.categories().stream().filter(c -> c.id().equals(id)).findFirst();
    }

    private static String name(String raw) {
        String name = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > MAX_CATEGORY_NAME) {
            throw badRequest("Der Name braucht 1 bis 40 Zeichen.");
        }
        return name;
    }

    private static void requireUnique(FocusData data, String name, String exceptId) {
        String lower = name.toLowerCase(Locale.GERMANY);
        boolean taken = data.categories().stream()
                .anyMatch(c -> !c.deleted() && !c.id().equals(exceptId) && c.name().toLowerCase(Locale.GERMANY).equals(lower));
        if (taken) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Diese Kategorie gibt es schon.");
        }
    }

    LocalDate dayOf(FocusSession s) {
        return s.start().atZone(clock.getZone()).toLocalDate();
    }

    private FocusSessionView view(FocusData data, FocusSession s) {
        String categoryName = s.categoryId() == null ? null : find(data, s.categoryId()).map(FocusCategory::name).orElse(null);
        return FocusSessionView.of(s, dayOf(s), categoryName);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record Recorded(FocusSessionView session, boolean created) {
    }

    public record CategoryCreated(FocusCategoryView category, boolean created) {
    }
}
