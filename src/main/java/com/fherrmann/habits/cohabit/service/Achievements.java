package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.EventKind;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.Setting;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.CohabitEval.MemberEval;
import com.fherrmann.habits.cohabit.rules.StreakModel;
import com.fherrmann.habits.cohabit.rules.StreakUnit;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Neue Bestserien und Meilensteine: Systemmeldung, Timeline-Ereignis und ein Push
 * an die anderen. Je Serie wird die Bestserie einmal gemeldet - in dem Moment, in
 * dem sie den alten Rekord uebertrifft -, sonst kaeme jeden Tag eine Meldung.
 */
@Service
public class Achievements {

    private final EventService events;
    private final Notifier notifier;

    public Achievements(EventService events, Notifier notifier) {
        this.events = events;
        this.notifier = notifier;
    }

    /** Prueft eine Person nach einer Aenderung (Eintrag, Health-Wert, Scheduler). */
    public void check(CohabitStore.Tx tx, CohabitEval eval, String personId, Instant now) {
        Cohabit c = eval.cohabit;
        MemberEval me = eval.member(personId);
        if (me == null || c.archived || (c.type != CohabitType.STREAK && c.type != CohabitType.ABSTINENCE)) {
            return;
        }
        Member m = me.member;
        if (m.baselinePending) {
            if (me.unavailable == null) {
                tx.cohabitsW();
                Milestones.silentBaseline(c, m, eval.checkins, me.facts, eval.today);
            }
            return;
        }
        if (c.type == CohabitType.STREAK) {
            if (me.unavailable != null) {
                return;
            }
            StreakUnit unit = StreakModel.scheme(c).unit();
            int current = me.streak.current();
            LocalDate runStart = me.streak.runStart();
            if (current > m.bestStreak) {
                tx.cohabitsW();
                boolean announce = current >= 2 && runStart != null && !runStart.equals(m.bestAnnouncedRun);
                m.bestStreak = current;
                if (announce) {
                    m.bestAnnouncedRun = runStart;
                    announce(tx, c, personId, EventKind.NEW_BEST, current, unit, now);
                }
            }
            milestones(tx, c, m, personId, current, runStart, unit, now);
        } else {
            int current = me.abstinence.current();
            if (current > m.bestStreak) {
                tx.cohabitsW();
                m.bestStreak = current;
            }
            milestones(tx, c, m, personId, current, me.abstinence.runStart(), StreakUnit.DAYS, now);
        }
    }

    private void milestones(CohabitStore.Tx tx, Cohabit c, Member m, String personId, int current, LocalDate runStart,
                            StreakUnit unit, Instant now) {
        if (runStart == null || current <= 0) {
            return;
        }
        String key = runStart.toString();
        List<Integer> reached = Milestones.reached(c.type, unit, current);
        List<Integer> known = m.milestones.getOrDefault(key, List.of());
        List<Integer> fresh = reached.stream().filter(v -> !known.contains(v)).toList();
        if (fresh.isEmpty()) {
            return;
        }
        tx.cohabitsW();
        // Nur die laufende Serie merken - die Datei soll nicht mit jeder Serie wachsen.
        m.milestones.keySet().removeIf(k -> !k.equals(key));
        List<Integer> all = new ArrayList<>(known);
        all.addAll(fresh);
        m.milestones.put(key, all);
        announce(tx, c, personId, EventKind.MILESTONE, fresh.getLast(), unit, now);
    }

    private void announce(CohabitStore.Tx tx, Cohabit c, String personId, EventKind kind, int value, StreakUnit unit,
                          Instant now) {
        String name = Views.name(tx, personId);
        String amount = value + " " + unit.label(value);
        Event e = events.event(tx, c, kind, personId, now);
        e.value = (double) value;
        e.detail = amount;
        String text = kind == EventKind.NEW_BEST
                ? name + " hat eine neue Bestserie: " + amount
                : name + " hat " + amount + " geschafft";
        events.system(tx, c, text, personId, now);
        List<String> others = c.members.stream().map(x -> x.personId).filter(id -> !id.equals(personId)).toList();
        notifier.notify(tx, others, new PushMessage("milestone", text, c.name, c.id, "cohabit://timeline"),
                Setting.MILESTONES, c, personId);
    }
}
