package com.fherrmann.habits.service;

import com.fherrmann.habits.dto.FocusSessionRequest;
import com.fherrmann.habits.dto.FocusSessionView;
import com.fherrmann.habits.model.FocusData;
import com.fherrmann.habits.model.FocusSession;
import com.fherrmann.habits.repository.FocusRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Die Fokus-Sessions: annehmen, auflisten, je Tag summieren.
 *
 * <p>Eine Session gehoert zu dem Tag, an dem sie <em>begann</em> (in
 * {@code habits.zone}). Wer um 23:30 pflanzt und um 0:30 fertig ist, hat
 * am Abend fokussiert - nicht am naechsten Morgen.
 */
@Service
public class FocusService {

    /** Kuerzer geht keine Session - die App laesst weniger gar nicht erst zu. */
    static final int MIN_MINUTES = 30;
    /** Laenger als einen Tag ist keine Session, sondern ein Fehler im Client. */
    static final int MAX_MINUTES = 24 * 60;
    /** So viel darf das Ende in der Zukunft liegen - Uhren gehen nie ganz gleich. */
    static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final FocusRepository repository;
    private final Clock clock;

    public FocusService(FocusRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    /**
     * Nimmt eine abgeschlossene Session an. Dieselbe Id noch einmal - etwa
     * aus dem Postausgang der App nachgesendet - aendert nichts und liefert
     * den vorhandenen Baum.
     *
     * @return die Session und ob sie neu war
     */
    public Recorded record(FocusSessionRequest request) {
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
            return new Recorded(view(existing.get()), false);
        }
        long minutes = Duration.between(request.start(), request.end()).toMinutes();
        if (minutes < MIN_MINUTES) {
            throw badRequest("Eine Session dauert mindestens " + MIN_MINUTES + " Minuten.");
        }
        if (minutes > MAX_MINUTES) {
            throw badRequest("Eine Session dauert höchstens einen Tag.");
        }
        if (request.end().isAfter(Instant.now(clock).plus(CLOCK_SKEW))) {
            throw badRequest("Die Session ist noch nicht vorbei.");
        }
        FocusSession session = new FocusSession(request.id().trim(), request.start(), request.end());
        List<FocusSession> sessions = new ArrayList<>(data.sessions());
        sessions.add(session);
        repository.save(new FocusData(sessions));
        return new Recorded(view(session), true);
    }

    /** Sessions, deren Tag im Zeitraum liegt - neueste zuerst. */
    public List<FocusSessionView> list(LocalDate from, LocalDate to) {
        if (from == null || to == null || to.isBefore(from)) {
            throw badRequest("from und to sind Pflicht, und to darf nicht vor from liegen.");
        }
        return repository.load().sessions().stream()
                .map(this::view)
                .filter(v -> !v.day().isBefore(from) && !v.day().isAfter(to))
                .sorted(Comparator.comparing(FocusSessionView::start).reversed())
                .toList();
    }

    /** Fokus-Minuten je Tag im Zeitraum; Tage ohne Session fehlen. */
    public Map<LocalDate, Integer> minutesPerDay(LocalDate from, LocalDate to) {
        Map<LocalDate, Integer> perDay = new HashMap<>();
        for (FocusSession s : repository.load().sessions()) {
            LocalDate day = dayOf(s);
            if (!day.isBefore(from) && !day.isAfter(to)) {
                perDay.merge(day, s.minutes(), Integer::sum);
            }
        }
        return perDay;
    }

    LocalDate dayOf(FocusSession s) {
        return s.start().atZone(clock.getZone()).toLocalDate();
    }

    private FocusSessionView view(FocusSession s) {
        return FocusSessionView.of(s, dayOf(s));
    }

    private static ResponseStatusException badRequest(String reason) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason);
    }

    public record Recorded(FocusSessionView session, boolean created) {
    }
}
