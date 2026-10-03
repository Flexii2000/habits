package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.CohabitRef;
import com.fherrmann.habits.cohabit.api.TimelineItem;
import com.fherrmann.habits.cohabit.api.TimelinePage;
import com.fherrmann.habits.cohabit.model.ChallengeRound;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Event;
import com.fherrmann.habits.cohabit.model.EventKind;
import com.fherrmann.habits.cohabit.rules.CohabitEval;
import com.fherrmann.habits.cohabit.rules.Texts;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

/**
 * Die Timeline: Ereignisse aller eigenen Co-Habits, neueste zuerst - keine
 * Chatnachrichten. Titel und Unterzeile entstehen beim Lesen.
 */
@Service
public class TimelineService {

    static final int DEFAULT_LIMIT = 30;
    static final int MAX_LIMIT = 100;

    private final CohabitStore store;

    public TimelineService(CohabitStore store) {
        this.store = store;
    }

    public TimelinePage page(Viewer viewer, String cohabitId, String before, Integer limit) {
        return page(viewer, cohabitId, Set.of(), before, limit);
    }

    /** @param exclude Co-Habits, die nicht erscheinen sollen (Filter der Clients) */
    public TimelinePage page(Viewer viewer, String cohabitId, Set<String> exclude, String before, Integer limit) {
        String me = viewer.personId();
        int max = limit == null ? DEFAULT_LIMIT : Math.max(1, Math.min(MAX_LIMIT, limit));
        return store.read(data -> {
            if (cohabitId != null && !cohabitId.isBlank()) {
                ViewService.visible(data, cohabitId, me);
            }
            Set<String> blocked = data.person(me).map(p -> Set.copyOf(p.blocked)).orElse(Set.of());
            List<Event> events = data.events().events;
            List<Integer> order = new ArrayList<>();
            for (int i = 0; i < events.size(); i++) {
                Event e = events.get(i);
                if (cohabitId != null && !cohabitId.isBlank() && !e.cohabitId.equals(cohabitId)) {
                    continue;
                }
                if (exclude.contains(e.cohabitId)) {
                    continue;
                }
                if (e.personId != null && blocked.contains(e.personId)) {
                    continue;
                }
                Cohabit c = data.cohabit(e.cohabitId).orElse(null);
                if (c != null && c.isMember(me)) {
                    order.add(i);
                }
            }
            // Neueste zuerst; bei gleichem Zeitpunkt das spaeter angelegte oben.
            order.sort(Comparator.comparing((Integer i) -> events.get(i).at).thenComparing(i -> i).reversed());
            int start = 0;
            if (before != null && !before.isBlank()) {
                start = order.size();
                for (int k = 0; k < order.size(); k++) {
                    if (events.get(order.get(k)).id.equals(before)) {
                        start = k + 1;
                        break;
                    }
                }
            }
            int end = Math.min(order.size(), start + max);
            List<TimelineItem> items = new ArrayList<>();
            for (int k = start; k < end; k++) {
                Event e = events.get(order.get(k));
                items.add(item(data, data.cohabit(e.cohabitId).orElseThrow(), e, me));
            }
            return new TimelinePage(items, end < order.size());
        });
    }

    public static TimelineItem item(CohabitStore.Data data, Cohabit c, Event e, String me) {
        ZoneId zone = CohabitEval.zoneOf(c);
        String time = Texts.time(e.at, zone);
        String name = e.personId == null ? null : Views.name(data, e.personId);
        String title;
        String subtitle;
        switch (e.kind) {
            case CHECKIN, PHOTO_CHECKIN -> {
                title = TimelineTexts.checkinTitle(c, name, e.value);
                if (c.type != CohabitType.STREAK && e.detail != null) {
                    title = title + " · " + e.detail;
                }
                if (c.type == CohabitType.CHALLENGE && c.challenge != null && c.challenge.runPoints()) {
                    List<Checkin> all = data.checkins(c.id);
                    Checkin run = all.stream().filter(x -> x.id.equals(e.checkinId)).findFirst().orElse(null);
                    String runTitle = TimelineTexts.runTitle(c, name, run, all);
                    title = runTitle == null ? title : runTitle;
                }
                subtitle = time + " · " + (c.type == CohabitType.STREAK && e.detail != null ? e.detail : c.name);
                if (e.checkinDate != null && !e.checkinDate.equals(e.day)) {
                    subtitle = subtitle + " · für " + Texts.dateShort(e.checkinDate);
                }
            }
            case HEALTH -> {
                String amount = e.value == null ? "" : Texts.valueText(e.value, c.tracking.unit());
                title = "Health hat " + amount + " für " + (me.equals(e.personId) ? "dich" : name) + " eingetragen";
                subtitle = time + " · " + c.name;
                if (e.checkinDate != null && !e.checkinDate.equals(e.day)) {
                    subtitle = subtitle + " · für " + Texts.dateShort(e.checkinDate);
                }
            }
            case MILESTONE -> {
                title = name + " hat " + e.detail + " " + c.name + " geschafft";
                subtitle = time + " · Meilenstein";
            }
            case NEW_BEST -> {
                title = name + " hat eine neue Bestserie: " + e.detail;
                subtitle = time + " · " + c.name;
            }
            case CHALLENGE_ENDED -> {
                ChallengeRound round = c.challengeState == null || e.value == null ? null
                        : c.challengeState.rounds.stream().filter(r -> r.number == e.value.intValue())
                        .findFirst().orElse(null);
                title = round == null ? "„" + c.name + "“ ist beendet" : Views.challengeTitle(data, c, round);
                subtitle = time + " · " + (e.detail == null ? c.name : e.detail);
            }
            case GOAL_FINISHED -> {
                boolean reached = c.goalState != null && c.goalState.reached;
                title = "„" + c.name + "“: " + (reached ? "Ziel erreicht" : "Ziel verfehlt");
                subtitle = time + (e.detail == null ? "" : " · " + e.detail);
            }
            case BREAK -> {
                title = name + " hat „" + c.name + "“ unterbrochen";
                subtitle = time + (e.checkinDate != null && !e.checkinDate.equals(e.day)
                        ? " · für " + Texts.dateShort(e.checkinDate) : "");
            }
            default -> {
                title = c.name;
                subtitle = time;
            }
        }
        return new TimelineItem(e.id, e.day, e.at, CohabitRef.of(c), e.kind.name(), Views.person(data, e.personId),
                title, subtitle, e.photoId, e.caption, "event:" + e.id, ReactionService.views(e.reactions, me),
                !c.archived && c.isMember(me));
    }

    public void seen(Viewer viewer, String lastEventId) {
        String me = viewer.personId();
        store.update(tx -> {
            if (lastEventId == null || tx.events().events.stream().noneMatch(e -> e.id.equals(lastEventId))) {
                throw Errors.badRequest("Unbekanntes Ereignis.");
            }
            tx.eventsW().timelineSeen.put(me, lastEventId);
        });
    }

    static boolean isPhoto(Event e) {
        return e.kind == EventKind.PHOTO_CHECKIN;
    }
}
