package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.api.LogbookBehaviorView;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.LimitConfig;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.MemberEval;
import com.fherrmann.habits.cohabit.rules.Limits;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Zaehlen bei einer Abstinenz mit Tageslimit. Die Zahl eines Tages steht im Logbook; coHabit
 * schreibt sie dorthin - als ganze Zahl des Tages, nicht als „eins mehr“, damit ein
 * nachgesendeter Aufruf aus dem Postausgang nichts doppelt zaehlt. Keine Timeline, kein Chat,
 * kein Push je Tasse.
 */
@Service
public class LimitService {

    /** Wie im Logbook: Mengen bis 10.000. */
    static final double MAX_COUNT = 10_000;

    private final CohabitStore store;
    private final ViewService views;
    private final LogbookSource logbook;
    private final Achievements achievements;

    public LimitService(CohabitStore store, ViewService views, LogbookSource logbook, Achievements achievements) {
        this.store = store;
        this.views = views;
        this.logbook = logbook;
        this.achievements = achievements;
    }

    public CohabitDetail count(Viewer viewer, String cohabitId, LocalDate date, Double count) {
        String me = viewer.personId();
        Instant now = views.now();
        if (count == null || count.isNaN() || count < 0 || count > MAX_COUNT) {
            throw Errors.badRequest("Die Zahl muss zwischen 0 und 10.000 liegen.");
        }
        String behaviorId = store.read(data -> {
            Cohabit c = ViewService.visible(data, cohabitId, me);
            LimitConfig limit = Limits.of(c);
            if (limit == null) {
                throw Errors.badRequest("Dieses Co-Habit zählt nicht aus dem Logbook.");
            }
            if (c.archived) {
                throw Errors.conflict("Das Co-Habit ist archiviert.");
            }
            // Nur fuer die Fristen - die Zahlen braucht es dafuer nicht.
            CohabitEval e = CohabitEval.evaluate(c, List.of(), Map.of(), now);
            MemberEval mine = e.member(me);
            if (date == null || date.isAfter(e.today) || date.isBefore(Views.backfillFrom(e, mine))) {
                throw Errors.badRequest("Außerhalb der Nachtragsfrist.");
            }
            return limit.behaviorId();
        });
        logbook.setValue(me, behaviorId, date, count);
        ViewService.Facts facts = views.factsForCohabit(cohabitId);
        store.update(tx -> tx.cohabit(cohabitId).filter(c -> c.isMember(me))
                .ifPresent(c -> achievements.check(tx, views.evaluate(tx, c, facts, now), me, now)));
        return store.read(data -> views.detail(data, me, cohabitId, facts));
    }

    /** Die aktiven Verhaltensweisen mit Menge aus dem eigenen Logbook - zur Wahl fuer ein Tageslimit. */
    public List<LogbookBehaviorView> behaviors(Viewer viewer) {
        String me = viewer.personId();
        if (!logbook.hasLogbook(me)) {
            throw Errors.forbidden("Dafür braucht es einen Healthy-Zugang.");
        }
        return logbook.amountBehaviors(me).stream()
                .map(b -> new LogbookBehaviorView(b.id(), b.name(), b.unit()))
                .toList();
    }
}
