package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.ClassicHabit;
import com.fherrmann.habits.cohabit.service.ClassicService;
import com.fherrmann.habits.security.Viewer;
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
 * Die klassische Liste der iOS-App - dieselben Pfade und Formen wie die alte
 * Habits-API ({@code /habits/api/habits}), nur unter coHabit und je Person.
 */
@RestController
@RequestMapping("/cohabit/api/classic/habits")
public class ClassicController {

    private final ClassicService classic;

    public ClassicController(ClassicService classic) {
        this.classic = classic;
    }

    @GetMapping
    public List<ClassicHabit> list(Viewer viewer) {
        return classic.list(viewer);
    }

    @PostMapping
    public ResponseEntity<ClassicHabit> create(Viewer viewer, @RequestBody ClassicService.Draft draft) {
        return ResponseEntity.status(HttpStatus.CREATED).body(classic.create(viewer, draft));
    }

    @PutMapping("/{id}")
    public ClassicHabit update(Viewer viewer, @PathVariable("id") String id,
                               @RequestBody ClassicService.Draft draft) {
        return classic.update(viewer, id, draft);
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(Viewer viewer, @PathVariable("id") String id) {
        classic.delete(viewer, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/marks")
    public ClassicHabit mark(Viewer viewer, @PathVariable("id") String id,
                             @RequestBody(required = false) ClassicService.Mark mark) {
        return classic.mark(viewer, id, mark);
    }

    @DeleteMapping("/{id}/marks/{date}")
    public ClassicHabit unmark(Viewer viewer, @PathVariable("id") String id,
                               @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return classic.unmark(viewer, id, date);
    }
}
