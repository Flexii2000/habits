package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.AppLinkCreated;
import com.fherrmann.habits.cohabit.api.AppLinkView;
import com.fherrmann.habits.cohabit.api.DaysView;
import com.fherrmann.habits.cohabit.api.MeView;
import com.fherrmann.habits.cohabit.api.NotificationSettings;
import com.fherrmann.habits.cohabit.service.DaysService;
import com.fherrmann.habits.cohabit.service.PeopleService;
import com.fherrmann.habits.security.AuthCookies;
import com.fherrmann.habits.security.AuthVia;
import com.fherrmann.habits.security.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
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
import java.util.Map;

/** Ich, Profil, App-Links und Geraete. */
@RestController
@RequestMapping("/cohabit/api")
public class MeController {

    public record ProfileRequest(String displayName, String username) {
    }

    public record LabelRequest(String label) {
    }

    public record DeviceRequest(String token, String platform) {
    }

    private final PeopleService people;
    private final DaysService daySeries;

    public MeController(PeopleService people, DaysService days) {
        this.people = people;
        this.daySeries = days;
    }

    @GetMapping("/me")
    public MeView me(Viewer viewer) {
        return people.me(viewer);
    }

    @PutMapping("/me")
    public MeView update(Viewer viewer, @RequestBody ProfileRequest request) {
        return people.updateMe(viewer, request.displayName(), request.username());
    }

    /** Die eigenen Farben je Typ - alle fuenf Plaetze, Vorgaben eingesetzt. */
    @GetMapping("/me/type-colors")
    public Map<String, String> typeColors(Viewer viewer) {
        return people.typeColors(viewer);
    }

    @PutMapping("/me/type-colors")
    public Map<String, String> updateTypeColors(Viewer viewer, @RequestBody Map<String, String> changes) {
        return people.updateTypeColors(viewer, changes);
    }

    /**
     * Jedes eigene Co-Habit als Tagesreihe, aktiv und archiviert - das Logbook in Healthy
     * rechnet sie gegen die Recovery (der Weight Tracker fragt je Person mit ihrem Token).
     */
    @GetMapping("/me/days")
    public DaysView days(Viewer viewer,
                         @RequestParam("from") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                         @RequestParam("to") @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return daySeries.days(viewer, from, to);
    }

    @GetMapping("/me/notifications")
    public NotificationSettings notifications(Viewer viewer) {
        return people.notifications(viewer);
    }

    @PutMapping("/me/notifications")
    public NotificationSettings updateNotifications(Viewer viewer, @RequestBody NotificationSettings settings) {
        return people.updateNotifications(viewer, settings);
    }

    @GetMapping("/me/app-links")
    public List<AppLinkView> appLinks(Viewer viewer) {
        return people.appLinks(viewer);
    }

    @PostMapping("/me/app-links")
    public AppLinkCreated createAppLink(Viewer viewer, @RequestBody(required = false) LabelRequest request) {
        return people.createAppLink(viewer, request == null ? null : request.label());
    }

    /** {@code current} widerruft den Token dieser Anfrage; im Web faellt dazu das Cookie weg. */
    @DeleteMapping("/me/app-links/{id}")
    public ResponseEntity<Void> deleteAppLink(Viewer viewer, @PathVariable("id") String id,
                                              HttpServletRequest request) {
        if ("current".equals(id)) {
            people.revokeCurrent(viewer);
            ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
            if (viewer.via() == AuthVia.COOKIE_APP) {
                response.header(HttpHeaders.SET_COOKIE, AuthCookies.clearCohabit(request).toString());
            }
            return response.build();
        }
        people.deleteAppLink(viewer, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/devices")
    public ResponseEntity<Void> registerDevice(Viewer viewer, @RequestBody DeviceRequest request) {
        people.registerDevice(viewer, request.token(), request.platform());
        return ResponseEntity.status(HttpStatus.NO_CONTENT).build();
    }

    @DeleteMapping("/devices/{token}")
    public ResponseEntity<Void> unregisterDevice(Viewer viewer, @PathVariable("token") String token) {
        people.unregisterDevice(viewer, token);
        return ResponseEntity.noContent().build();
    }
}
