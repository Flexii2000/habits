package com.fherrmann.habits.controller;

import com.fherrmann.habits.dto.FocusCategoryRequest;
import com.fherrmann.habits.dto.FocusCategoryView;
import com.fherrmann.habits.dto.FocusSessionRequest;
import com.fherrmann.habits.dto.FocusSessionView;
import com.fherrmann.habits.service.FocusService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Der Wald der Fokus-App: jede durchgestandene Session ist ein Baum. Die App
 * meldet sie hier, der Wald liest sie hier - und das Habit "Fokus-Zeit"
 * rechnet aus denselben Sessions.
 */
@RestController
@RequestMapping("/habits/api/focus")
public class FocusController {

    private final FocusService service;

    public FocusController(FocusService service) {
        this.service = service;
    }

    /** 201 fuer einen neuen Baum, 200 fuer einen schon bekannten (gleiche Id). */
    @PostMapping("/sessions")
    public ResponseEntity<FocusSessionView> record(@RequestBody FocusSessionRequest request) {
        FocusService.Recorded recorded = service.record(request);
        return ResponseEntity.status(recorded.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .body(recorded.session());
    }

    /** Einen Baum faellen - fuer Testbaeume von frueher; die App bietet es nicht an. */
    @DeleteMapping("/sessions/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/sessions")
    public List<FocusSessionView> list(
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(from, to);
    }

    // MARK: - Kategorien (seit 01.10.2026)

    /** Die Kategorien zur Auswahl vor dem Pflanzen, in der Reihenfolge des Anlegens. */
    @GetMapping("/categories")
    public List<FocusCategoryView> categories() {
        return service.categories();
    }

    /** 201 fuer eine neue Kategorie, 200 fuer eine schon bekannte Id (Postausgang); 409 bei doppeltem Namen. */
    @PostMapping("/categories")
    public ResponseEntity<FocusCategoryView> createCategory(@RequestBody FocusCategoryRequest request) {
        FocusService.CategoryCreated created = service.createCategory(request == null ? null : request.id(),
                request == null ? null : request.name());
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(created.category());
    }

    @PutMapping("/categories/{id}")
    public FocusCategoryView renameCategory(@PathVariable("id") String id, @RequestBody FocusCategoryRequest request) {
        return service.renameCategory(id, request == null ? null : request.name());
    }

    /** Nur aus der Auswahl: Baeume und Co-Habits mit dieser Kategorie behalten ihren Namen. */
    @DeleteMapping("/categories/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable("id") String id) {
        service.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
