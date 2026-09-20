package com.fherrmann.habits.controller;

import com.fherrmann.habits.dto.FocusSessionRequest;
import com.fherrmann.habits.dto.FocusSessionView;
import com.fherrmann.habits.service.FocusService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
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
@RequestMapping("/api/focus")
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

    @GetMapping("/sessions")
    public List<FocusSessionView> list(
            @RequestParam(name = "from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(name = "to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return service.list(from, to);
    }
}
