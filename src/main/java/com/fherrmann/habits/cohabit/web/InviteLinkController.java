package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.AcceptResult;
import com.fherrmann.habits.cohabit.api.InviteLinkPreview;
import com.fherrmann.habits.cohabit.api.LinkView;
import com.fherrmann.habits.cohabit.service.InviteLinkService;
import com.fherrmann.habits.security.AuthCookies;
import com.fherrmann.habits.security.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Einladungs- und Freundes-Links; Vorschau und Annehmen gehen ohne Anmeldung. */
@RestController
@RequestMapping("/cohabit/api")
public class InviteLinkController {

    private final InviteLinkService links;

    public InviteLinkController(InviteLinkService links) {
        this.links = links;
    }

    @GetMapping("/invite-links/{code}")
    public InviteLinkPreview preview(@PathVariable("code") String code) {
        return links.preview(code);
    }

    /**
     * Wer neu ist, bekommt einen Token: in der Antwort (Apps) und als
     * {@code cohabit_token}-Cookie (Web) - die jeweils andere Seite ignoriert es.
     */
    @PostMapping("/invite-links/{code}/accept")
    public ResponseEntity<AcceptResult> accept(Viewer viewer, @PathVariable("code") String code,
                                               @RequestBody(required = false) InviteLinkService.Registration body,
                                               HttpServletRequest request) {
        InviteLinkService.Accepted accepted = links.accept(code, viewer, body);
        ResponseEntity.BodyBuilder response = ResponseEntity.ok();
        if (accepted.token() != null) {
            response.header(HttpHeaders.SET_COOKIE, AuthCookies.cohabit(accepted.token(), request).toString());
        }
        return response.body(accepted.result());
    }

    @PostMapping("/me/friend-link")
    public LinkView friendLink(Viewer viewer) {
        return links.friendLink(viewer);
    }
}
