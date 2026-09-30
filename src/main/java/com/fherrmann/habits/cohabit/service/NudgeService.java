package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.NudgeView;
import com.fherrmann.habits.cohabit.api.CohabitSummary;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.Nudge;
import com.fherrmann.habits.cohabit.push.Notifier;
import com.fherrmann.habits.cohabit.push.PushMessage;
import com.fherrmann.habits.cohabit.push.Setting;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Stupser: an ein Mitglied, das heute noch offen ist, einer je Absender, Empfaenger,
 * Co-Habit und Tag. Zurueckstupsen geht immer - auch wenn der Absender schon erledigt hat.
 */
@Service
public class NudgeService {

    static final int MAX_TEXT = 60;

    private final CohabitStore store;
    private final ViewService views;
    private final Notifier notifier;

    public NudgeService(CohabitStore store, ViewService views, Notifier notifier) {
        this.store = store;
        this.views = views;
        this.notifier = notifier;
    }

    public NudgeView nudge(Viewer viewer, String cohabitId, String to, String text) {
        String me = viewer.personId();
        String message = text == null || text.isBlank() ? null : text.strip();
        if (message != null && message.length() > MAX_TEXT) {
            throw Errors.badRequest("Ein Stupser hat höchstens 60 Zeichen.");
        }
        ViewService.Facts facts = views.factsForCohabit(cohabitId);
        Instant now = views.now();
        return store.write(tx -> {
            Cohabit c = ViewService.visible(tx, cohabitId, me);
            if (to == null || to.equals(me) || !c.isMember(to)) {
                throw Errors.badRequest("Anstupsen geht nur bei anderen Mitgliedern.");
            }
            if (Social.blockedEitherWay(tx, me, to)) {
                throw Errors.notFound("Person nicht gefunden.");
            }
            if (c.archived) {
                throw Errors.conflict("Das Co-Habit ist archiviert.");
            }
            LocalDate today = LocalDate.ofInstant(now, CohabitEval.zoneOf(c));
            List<Nudge> nudges = tx.events().nudges;
            if (nudges.stream().anyMatch(n -> n.cohabitId.equals(c.id) && n.fromId.equals(me) && n.toId.equals(to)
                    && n.day.equals(today))) {
                throw Errors.tooMany("Heute schon angestupst.");
            }
            boolean nudgeBack = nudges.stream().anyMatch(n -> n.cohabitId.equals(c.id) && n.fromId.equals(to)
                    && n.toId.equals(me) && n.day.equals(today));
            CohabitSummary theirs = Views.summary(tx, views.evaluate(tx, c, facts, now), to);
            if (!nudgeBack && !theirs.status().equals("OPEN")) {
                throw Errors.badRequest("Heute ist da nichts mehr offen.");
            }
            Nudge n = new Nudge();
            n.id = UUID.randomUUID().toString();
            n.cohabitId = c.id;
            n.fromId = me;
            n.toId = to;
            n.day = today;
            n.text = message == null ? "Heute noch „" + c.name + "“?" : message;
            n.createdAt = now;
            tx.eventsW().nudges.add(n);
            notifier.notify(tx, List.of(to), new PushMessage("nudge", Views.name(tx, me) + " hat dich angestupst",
                    n.text, c.id, "cohabit://today"), Setting.NUDGES, c, me);
            return OverviewService.nudgeView(tx, n, c);
        });
    }

    public void seen(Viewer viewer, String nudgeId) {
        String me = viewer.personId();
        Instant now = views.now();
        store.update(tx -> {
            Nudge n = tx.events().nudges.stream().filter(x -> x.id.equals(nudgeId) && x.toId.equals(me)).findFirst()
                    .orElseThrow(() -> Errors.notFound("Stupser nicht gefunden."));
            if (n.seenAt == null) {
                tx.eventsW();
                n.seenAt = now;
            }
        });
    }
}
