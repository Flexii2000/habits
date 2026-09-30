package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.ShiftableClock;
import com.fherrmann.habits.cohabit.service.Errors;
import com.fherrmann.habits.security.Viewer;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

/**
 * Nur fuer lokale Instanzen ({@code cohabit.dev.enabled=true}): die Uhr verschieben
 * und den Scheduler sofort laufen lassen - so entstehen Demo-Daten mit echter
 * Geschichte (ein Co-Habit von vor zwei Wochen, eine beendete Challenge-Runde).
 * Auf dem Server gibt es diesen Controller nicht.
 */
@RestController
@RequestMapping("/cohabit/api/dev")
@ConditionalOnProperty(name = "cohabit.dev.enabled", havingValue = "true")
public class DevController {

    /** Was der Scheduler bei einem Lauf tut - entkoppelt, damit es ihn auch ohne Scheduler gibt. */
    public interface Tickable {
        void tick(Instant now);
    }

    public record ClockRequest(Long offsetSeconds) {
    }

    private final ShiftableClock clock;
    private final ObjectProvider<Tickable> scheduler;

    public DevController(Clock clock, ObjectProvider<Tickable> scheduler) {
        if (!(clock instanceof ShiftableClock shiftable)) {
            throw new IllegalStateException("cohabit.dev.enabled braucht die verschiebbare Uhr");
        }
        this.clock = shiftable;
        this.scheduler = scheduler;
    }

    private static void ownerOnly(Viewer viewer) {
        if (viewer == null || !viewer.owner()) {
            throw Errors.forbidden("Nur für die Eigentümerin.");
        }
    }

    @GetMapping("/clock")
    public Map<String, Object> get(Viewer viewer) {
        ownerOnly(viewer);
        return Map.of("now", clock.instant().toString(), "offsetSeconds", clock.offset().toSeconds());
    }

    @PostMapping("/clock")
    public Map<String, Object> set(Viewer viewer, @RequestBody ClockRequest request) {
        ownerOnly(viewer);
        clock.setOffset(Duration.ofSeconds(request.offsetSeconds() == null ? 0 : request.offsetSeconds()));
        return get(viewer);
    }

    @PostMapping("/tick")
    public Map<String, Object> tick(Viewer viewer) {
        ownerOnly(viewer);
        Tickable t = scheduler.getIfAvailable();
        if (t != null) {
            t.tick(clock.instant());
        }
        return Map.of("now", clock.instant().toString(), "ticked", t != null);
    }
}
