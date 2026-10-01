package com.fherrmann.habits.service;

import com.fherrmann.habits.dto.FocusCategoryView;
import com.fherrmann.habits.dto.FocusSessionRequest;
import com.fherrmann.habits.dto.FocusSessionView;
import com.fherrmann.habits.repository.FocusRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.json.JsonMapper;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FocusServiceTest {

    private static final ZoneId BERLIN = ZoneId.of("Europe/Berlin");
    // Mittwoch, 2. September 2026, 21:30 in Berlin.
    private static final ZonedDateTime NOW = ZonedDateTime.of(2026, 9, 2, 21, 30, 0, 0, BERLIN);
    private static final LocalDate TODAY = NOW.toLocalDate();

    @TempDir
    Path dir;

    private FocusService service;

    @BeforeEach
    void setUp() {
        FocusRepository repository = new FocusRepository(dir.resolve("focus.json").toString(),
                JsonMapper.builder().build());
        service = new FocusService(repository, Clock.fixed(NOW.toInstant(), BERLIN));
    }

    private static Instant at(int hour, int minute, int daysBack) {
        return NOW.toLocalDate().minusDays(daysBack).atTime(hour, minute).atZone(BERLIN).toInstant();
    }

    @Test
    void eineSessionWirdEinmalGepflanztAuchWennSieZweimalGemeldetWird() {
        FocusSessionRequest request = new FocusSessionRequest("s1", at(14, 0, 0), at(14, 45, 0));
        FocusService.Recorded first = service.record(request);
        FocusService.Recorded again = service.record(request);
        assertTrue(first.created());
        assertFalse(again.created(), "Nachsenden aus dem Postausgang darf keinen zweiten Baum pflanzen");
        assertEquals(45, first.session().minutes());
        assertEquals(TODAY, first.session().day());
        assertEquals(1, service.list(TODAY, TODAY).size());
    }

    @Test
    void zuKurzZuLangUndNochNichtVorbeiWerdenAbgelehnt() {
        assertThrows(ResponseStatusException.class,
                () -> service.record(new FocusSessionRequest("kurz", at(14, 0, 0), at(14, 0, 0))));
        // Ein Testbaum von einer Minute ist erlaubt - die 30 Minuten sind die
        // Regel der App, nicht des Dienstes.
        assertTrue(service.record(new FocusSessionRequest("test", at(14, 0, 0), at(14, 1, 0))).created());
        assertThrows(ResponseStatusException.class,
                () -> service.record(new FocusSessionRequest("lang", at(8, 0, 2), at(9, 0, 0))));
        assertThrows(ResponseStatusException.class,
                () -> service.record(new FocusSessionRequest("zukunft", at(21, 0, 0), at(22, 30, 0))));
        assertThrows(ResponseStatusException.class,
                () -> service.record(new FocusSessionRequest(" ", at(14, 0, 0), at(15, 0, 0))));
    }

    @Test
    void einBaumLaesstSichFaellenEinUnbekannterNicht() {
        service.record(new FocusSessionRequest("s1", at(14, 0, 0), at(14, 45, 0)));
        service.record(new FocusSessionRequest("s2", at(15, 0, 0), at(15, 45, 0)));
        service.delete("s1");
        assertEquals(List.of("s2"), service.list(TODAY, TODAY).stream().map(FocusSessionView::id).toList());
        assertThrows(ResponseStatusException.class, () -> service.delete("s1"));
    }

    @Test
    void sessionsGehoerenZumTagIhresBeginns() {
        // 23:30 gestern bis 0:30 heute: ein Abend, kein Morgen.
        service.record(new FocusSessionRequest("nacht", at(23, 30, 1), at(0, 30, 0)));
        service.record(new FocusSessionRequest("heute", at(9, 0, 0), at(10, 0, 0)));
        service.record(new FocusSessionRequest("alt", at(9, 0, 5), at(12, 0, 5)));

        Map<LocalDate, Integer> perDay = service.minutesPerDay(TODAY.minusDays(7), TODAY);
        assertEquals(60, perDay.get(TODAY.minusDays(1)));
        assertEquals(60, perDay.get(TODAY));
        assertEquals(180, perDay.get(TODAY.minusDays(5)));

        List<FocusSessionView> recent = service.list(TODAY.minusDays(1), TODAY);
        assertEquals(List.of("heute", "nacht"), recent.stream().map(FocusSessionView::id).toList(),
                "neueste zuerst");
    }

    // MARK: - Kategorien

    @Test
    void kategorienAnlegenUmbenennenUndNurMarkiertLoeschen() {
        FocusService.CategoryCreated thesis = service.createCategory("kat-bachelor-0001", "  Bachelorarbeit ");
        assertTrue(thesis.created());
        assertEquals("Bachelorarbeit", thesis.category().name(), "Leerzeichen am Rand fallen weg");
        assertFalse(service.createCategory("kat-bachelor-0001", "egal").created(), "dieselbe Id: Postausgang");
        String uni = service.createCategory(null, "Uni").category().id();
        assertEquals(List.of("Bachelorarbeit", "Uni"),
                service.categories().stream().map(FocusCategoryView::name).toList(), "Reihenfolge des Anlegens");

        ResponseStatusException twice = assertThrows(ResponseStatusException.class,
                () -> service.createCategory(null, "uni"));
        assertEquals(HttpStatus.CONFLICT, twice.getStatusCode(), "Gross/klein zaehlt nicht");
        assertThrows(ResponseStatusException.class, () -> service.createCategory(null, " "));
        assertThrows(ResponseStatusException.class, () -> service.createCategory(null, "x".repeat(41)));

        assertEquals("Studium", service.renameCategory(uni, "Studium").name());
        service.record(new FocusSessionRequest("baum-1", at(14, 0, 0), at(15, 0, 0), uni));
        service.deleteCategory(uni);
        assertEquals(List.of("Bachelorarbeit"), service.categories().stream().map(FocusCategoryView::name).toList());
        assertEquals("Studium", service.list(TODAY, TODAY).getFirst().categoryName(), "der Baum behaelt den Namen");
        assertEquals("Studium", service.categoryName(uni).orElseThrow());
        assertFalse(service.isActiveCategory(uni));
        assertThrows(ResponseStatusException.class, () -> service.renameCategory(uni, "Neu"));
        assertTrue(service.createCategory(null, "Studium").created(), "der Name ist wieder frei");
    }

    @Test
    void einBaumTraegtSeineKategorieUndDieMinutenLassenSichDanachZaehlen() {
        String thesis = service.createCategory(null, "Bachelorarbeit").category().id();
        String uni = service.createCategory(null, "Uni").category().id();
        service.record(new FocusSessionRequest("t1", at(9, 0, 0), at(10, 0, 0), thesis));
        service.record(new FocusSessionRequest("t2", at(11, 0, 0), at(11, 30, 0), thesis));
        service.record(new FocusSessionRequest("u1", at(14, 0, 0), at(14, 45, 0), uni));
        service.record(new FocusSessionRequest("ohne", at(16, 0, 0), at(16, 20, 0)));
        assertEquals(Map.of(TODAY, 90), service.minutesPerDay(TODAY, TODAY, thesis));
        assertEquals(Map.of(TODAY, 45), service.minutesPerDay(TODAY, TODAY, uni));
        assertEquals(Map.of(TODAY, 155), service.minutesPerDay(TODAY, TODAY), "ohne Kategorie: alle Baeume");
        FocusSessionView first = service.list(TODAY, TODAY).getLast();
        assertEquals(thesis, first.categoryId());
        assertEquals("Bachelorarbeit", first.categoryName());

        ResponseStatusException unknown = assertThrows(ResponseStatusException.class,
                () -> service.record(new FocusSessionRequest("x1", at(17, 0, 0), at(18, 0, 0), "gibt-es-nicht")));
        assertEquals(HttpStatus.BAD_REQUEST, unknown.getStatusCode());
    }
}
