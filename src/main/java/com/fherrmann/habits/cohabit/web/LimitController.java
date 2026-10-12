package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.api.LogbookBehaviorView;
import com.fherrmann.habits.cohabit.service.LimitService;
import com.fherrmann.habits.security.Viewer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;

/** Abstinenz mit Tageslimit: zaehlen - die Zahl landet im Logbook - und die Verhaltensweisen zur Wahl. */
@RestController
@RequestMapping("/cohabit/api")
public class LimitController {

    public record CountRequest(Double count) {
    }

    private final LimitService limits;

    public LimitController(LimitService limits) {
        this.limits = limits;
    }

    /** Die Zahl des Tages, absolut ({@code {"count": 3}}) - ein Nachsenden zaehlt nichts doppelt. */
    @PutMapping("/cohabits/{id}/count/{date}")
    public CohabitDetail count(Viewer viewer, @PathVariable("id") String id,
                               @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                               @RequestBody(required = false) CountRequest request) {
        return limits.count(viewer, id, date, request == null ? null : request.count());
    }

    @GetMapping("/logbook/behaviors")
    public List<LogbookBehaviorView> behaviors(Viewer viewer) {
        return limits.behaviors(viewer);
    }
}
