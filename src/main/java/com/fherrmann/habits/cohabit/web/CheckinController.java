package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.CheckinResult;
import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.service.CheckinService;
import com.fherrmann.habits.security.Viewer;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;

/** Eintraege und Health-Werte. */
@RestController
@RequestMapping("/cohabit/api/cohabits/{id}")
public class CheckinController {

    public record HealthRequest(Double value) {
    }

    private final CheckinService checkins;

    public CheckinController(CheckinService checkins) {
        this.checkins = checkins;
    }

    /** 201 fuer einen neuen Eintrag, 200 fuer eine schon bekannte ID (Postausgang der Apps). */
    @PostMapping("/checkins")
    public ResponseEntity<CheckinResult> create(Viewer viewer, @PathVariable("id") String id,
                                                @RequestBody(required = false) CheckinService.CheckinInput input) {
        CheckinService.Created created = checkins.create(viewer, id, input);
        return ResponseEntity.status(created.created() ? HttpStatus.CREATED : HttpStatus.OK).body(created.result());
    }

    @PutMapping("/checkins/{checkinId}")
    public CheckinResult update(Viewer viewer, @PathVariable("id") String id,
                                @PathVariable("checkinId") String checkinId,
                                @RequestBody CheckinService.CheckinInput input) {
        return checkins.update(viewer, id, checkinId, input);
    }

    @DeleteMapping("/checkins/{checkinId}")
    public CohabitDetail delete(Viewer viewer, @PathVariable("id") String id,
                                @PathVariable("checkinId") String checkinId) {
        return checkins.delete(viewer, id, checkinId);
    }

    @PutMapping("/health/{date}")
    public CohabitDetail health(Viewer viewer, @PathVariable("id") String id,
                                @PathVariable("date") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @RequestBody HealthRequest request) {
        return checkins.health(viewer, id, date, request.value());
    }
}
