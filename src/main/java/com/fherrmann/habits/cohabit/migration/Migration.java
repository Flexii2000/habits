package com.fherrmann.habits.cohabit.migration;

import com.fherrmann.habits.cohabit.model.AbstinenceConfig;
import com.fherrmann.habits.cohabit.model.AutoConfig;
import com.fherrmann.habits.cohabit.model.AutoSource;
import com.fherrmann.habits.cohabit.model.Checkin;
import com.fherrmann.habits.cohabit.model.CheckinKind;
import com.fherrmann.habits.cohabit.model.CheckinSource;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.CohabitType;
import com.fherrmann.habits.cohabit.model.Member;
import com.fherrmann.habits.cohabit.model.Palette;
import com.fherrmann.habits.cohabit.model.Rhythm;
import com.fherrmann.habits.cohabit.model.Role;
import com.fherrmann.habits.cohabit.model.StreakConfig;
import com.fherrmann.habits.cohabit.model.Tracking;
import com.fherrmann.habits.cohabit.service.Milestones;
import com.fherrmann.habits.cohabit.service.Persons;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.cohabit.store.JsonFiles;
import com.fherrmann.habits.legacy.Habit;
import com.fherrmann.habits.legacy.HabitKind;
import com.fherrmann.habits.legacy.HabitsData;
import com.fherrmann.habits.legacy.Mark;
import com.fherrmann.habits.legacy.Period;
import com.fherrmann.habits.security.HealthUsers;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Einmalig beim ersten Start: aus jedem Habit in {@code habits.json} wird ein
 * Co-Habit, Felix allein und Admin. Danach steht {@code migrated.marker} da, und
 * {@code habits.json} bleibt als Sicherung liegen - gelesen, nie geschrieben.
 *
 * <p>Laeuft, bevor der Webserver Anfragen annimmt (nach dem Anlegen aller Beans,
 * vor dem Start des Connectors) - niemand sieht einen halb migrierten Stand.
 *
 * <p>Keine Systemmeldungen, keine Timeline, keine Pushes: Bestserie und
 * Meilensteine der uebernommenen Serien werden still als "schon erreicht"
 * vermerkt, sonst feierte der erste Scheduler-Lauf Felix' alte Erfolge als neu.
 */
@Component
public class Migration implements SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(Migration.class);

    public static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    /** Die App erlaubte bisher 14 Tage rueckwirkend. */
    public static final int BACKFILL_HOURS = 336;

    private final CohabitStore store;
    private final Path habitsFile;
    private final HealthUsers users;
    private final Clock clock;

    public Migration(CohabitStore store, @Value("${habits.data-file:data/habits.json}") String habitsFile,
                     HealthUsers users, Clock clock) {
        this.store = store;
        this.habitsFile = Path.of(habitsFile);
        this.users = users;
        this.clock = clock;
    }

    public Path marker() {
        return store.dir().resolve("migrated.marker");
    }

    @Override
    public void afterSingletonsInstantiated() {
        run();
    }

    /** @return wie viele Co-Habits entstanden sind (0, wenn schon migriert oder nichts da war) */
    public synchronized int run() {
        if (Files.exists(marker())) {
            return 0;
        }
        int created = 0;
        if (Files.exists(habitsFile)) {
            HabitsData legacy = JsonFiles.read(store.mapper(), habitsFile, HabitsData.class, HabitsData.empty());
            created = migrate(legacy);
            log.info("Habits nach coHabit uebernommen: {} Co-Habits, {} Eintraege", created, legacy.marks().size());
        }
        writeMarker(created);
        return created;
    }

    private int migrate(HabitsData legacy) {
        String owner = users.owner();
        Instant now = Instant.now(clock).truncatedTo(ChronoUnit.SECONDS);
        LocalDate today = LocalDate.now(clock.withZone(BERLIN));
        return store.write(tx -> {
            if (tx.person(owner).isEmpty()) {
                Persons.create(tx, owner, Persons.displayNameFromId(owner), Persons.freeUsername(tx, owner), now);
            }
            int index = 0;
            for (Habit habit : legacy.habits()) {
                String id = "c-" + habit.id();
                if (!CohabitStore.isSafeId(id) || tx.cohabit(id).isPresent()) {
                    log.warn("Habit {} uebersprungen (ID unbrauchbar oder schon vorhanden)", habit.id());
                    continue;
                }
                Cohabit c = toCohabit(habit, id, owner, index++);
                tx.cohabitsW().cohabits.add(c);
                List<Checkin> checkins = tx.checkinsW(id);
                for (Mark mark : legacy.marks()) {
                    if (mark.habitId().equals(habit.id()) && habit.kind() != null && !habit.kind().isAutomatic()) {
                        checkins.add(toCheckin(mark, c, owner));
                    }
                }
                tx.messagesW(id);
                Member member = c.members.getFirst();
                if (c.auto != null) {
                    member.baselinePending = true;
                } else {
                    Milestones.silentBaseline(c, member, checkins, today);
                }
            }
            return index;
        });
    }

    static Cohabit toCohabit(Habit habit, String id, String owner, int index) {
        LocalDate start = habit.createdAt() == null ? LocalDate.now(BERLIN) : habit.createdAt();
        Instant createdAt = start.atTime(LocalTime.NOON).atZone(BERLIN).toInstant();
        Cohabit c = new Cohabit();
        c.id = id;
        c.name = habit.name() == null || habit.name().isBlank() ? "Habit" : trim(habit.name(), 40);
        c.color = Palette.roundRobin(index);
        c.timezone = BERLIN.getId();
        c.tracking = Tracking.CHECK;
        c.photoRequired = false;
        c.backfillHours = BACKFILL_HOURS;
        c.reminderTime = null;
        c.membersCanInvite = false;
        c.createdBy = owner;
        c.createdAt = createdAt;
        c.startDate = start;
        HabitKind kind = habit.kind() == null ? HabitKind.BUILD : habit.kind();
        switch (kind) {
            case BUILD -> {
                c.type = CohabitType.STREAK;
                Period period = habit.rhythm();
                Rhythm rhythm = switch (period) {
                    case DAY -> Rhythm.daily();
                    case WEEK -> Rhythm.timesPerWeek(habit.times());
                    case MONTH -> Rhythm.timesPerMonth(habit.times());
                };
                c.streak = new StreakConfig(rhythm, false);
            }
            case QUIT -> {
                c.type = CohabitType.ABSTINENCE;
                c.abstinence = new AbstinenceConfig(false);
            }
            case FOOD -> {
                c.type = CohabitType.STREAK;
                c.streak = new StreakConfig(Rhythm.daily(), false);
                c.auto = new AutoConfig(AutoSource.FOOD, null, null);
            }
            case STEPS -> {
                c.type = CohabitType.STREAK;
                c.streak = new StreakConfig(Rhythm.timesPerWeek(1), false);
                c.auto = new AutoConfig(AutoSource.STEPS_WEEKLY, habit.weeklyStepGoal(), null);
            }
            case FOCUS -> {
                c.type = CohabitType.STREAK;
                c.streak = new StreakConfig(Rhythm.daily(), false);
                c.auto = new AutoConfig(AutoSource.FOCUS, null,
                        habit.focusMinutesGoal() == null ? AutoConfig.DEFAULT_FOCUS_MINUTES : habit.focusMinutesGoal());
            }
        }
        Member member = new Member();
        member.personId = owner;
        member.role = Role.ADMIN;
        member.joinedAt = createdAt;
        member.startDate = start;
        c.members.add(member);
        return c;
    }

    static Checkin toCheckin(Mark mark, Cohabit c, String owner) {
        Checkin checkin = new Checkin();
        checkin.id = UUID.randomUUID().toString();
        checkin.cohabitId = c.id;
        checkin.personId = owner;
        checkin.kind = c.type == CohabitType.ABSTINENCE ? CheckinKind.BREAK : CheckinKind.DONE;
        checkin.date = mark.date();
        checkin.createdAt = mark.date().atTime(LocalTime.NOON).atZone(BERLIN).toInstant();
        checkin.source = CheckinSource.MIGRATED;
        return checkin;
    }

    private static String trim(String name, int max) {
        String t = name.trim();
        return t.length() <= max ? t : t.substring(0, max);
    }

    private void writeMarker(int created) {
        try {
            Files.createDirectories(store.dir());
            Files.writeString(marker(), "migrated " + Instant.now(clock).truncatedTo(ChronoUnit.SECONDS)
                    + " cohabits=" + created + "\n");
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write " + marker(), e);
        }
    }
}
