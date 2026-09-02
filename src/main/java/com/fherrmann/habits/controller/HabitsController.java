package com.fherrmann.habits.controller;

import com.fherrmann.habits.dto.HabitRequest;
import com.fherrmann.habits.dto.HabitStatus;
import com.fherrmann.habits.dto.MarkRequest;
import com.fherrmann.habits.service.HabitsService;
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
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/**
 * Die Schnittstelle fuer die App. Jede Antwort ist der fertige Stand
 * ({@link HabitStatus}), nie das rohe Habit - die App soll nichts nachrechnen.
 */
@RestController
@RequestMapping("/api/habits")
public class HabitsController {

    private final HabitsService service;

    public HabitsController(HabitsService service) {
        this.service = service;
    }

    @GetMapping
    public List<HabitStatus> list() {
        return service.list();
    }

    @PostMapping
    public ResponseEntity<HabitStatus> create(@RequestBody HabitRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(service.create(request));
    }

    @PutMapping("/{id}")
    public HabitStatus update(@PathVariable String id, @RequestBody HabitRequest request) {
        return service.update(id, request);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable String id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }

    /** Haken (BUILD) oder Rueckfall (QUIT) fuer einen Tag - ohne Datum: heute. */
    @PostMapping("/{id}/marks")
    public HabitStatus mark(@PathVariable String id, @RequestBody(required = false) MarkRequest request) {
        return service.mark(id, request == null ? null : request.date());
    }

    @DeleteMapping("/{id}/marks/{date}")
    public HabitStatus unmark(
            @PathVariable String id,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return service.unmark(id, date);
    }
}
