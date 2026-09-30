package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.StatsView;
import com.fherrmann.habits.cohabit.api.TodayView;
import com.fherrmann.habits.cohabit.api.WidgetData;
import com.fherrmann.habits.cohabit.service.OverviewService;
import com.fherrmann.habits.security.Viewer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Heute, Statistik, Widget. */
@RestController
@RequestMapping("/cohabit/api")
public class OverviewController {

    private final OverviewService overview;

    public OverviewController(OverviewService overview) {
        this.overview = overview;
    }

    @GetMapping("/today")
    public TodayView today(Viewer viewer) {
        return overview.today(viewer);
    }

    @GetMapping("/widget")
    public WidgetData widget(Viewer viewer) {
        return overview.widget(viewer);
    }

    @GetMapping("/stats")
    public StatsView stats(Viewer viewer, @RequestParam(name = "range", required = false) String range,
                           @RequestParam(name = "anchor", required = false)
                           @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate anchor) {
        return overview.stats(viewer, range, anchor);
    }
}
