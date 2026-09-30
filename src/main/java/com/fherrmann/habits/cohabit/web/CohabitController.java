package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.CohabitDetail;
import com.fherrmann.habits.cohabit.api.CohabitSummary;
import com.fherrmann.habits.cohabit.api.InvitationView;
import com.fherrmann.habits.cohabit.api.InviteCandidates;
import com.fherrmann.habits.cohabit.api.LinkView;
import com.fherrmann.habits.cohabit.service.CohabitInput;
import com.fherrmann.habits.cohabit.service.CohabitService;
import com.fherrmann.habits.security.Viewer;
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
import java.util.Map;

/** Co-Habits: anlegen, bearbeiten, Mitglieder, Einladungen, Pausen. */
@RestController
@RequestMapping("/cohabit/api")
public class CohabitController {

    public record ConfirmRequest(Boolean confirm) {
    }

    public record PersonIdsRequest(List<String> personIds) {
    }

    public record PersonRequest(String personId) {
    }

    public record PauseRequest(LocalDate from, LocalDate to) {
    }

    private final CohabitService cohabits;

    public CohabitController(CohabitService cohabits) {
        this.cohabits = cohabits;
    }

    @GetMapping("/cohabits")
    public List<CohabitSummary> list(Viewer viewer) {
        return cohabits.list(viewer);
    }

    @GetMapping("/me/archived")
    public List<CohabitSummary> archived(Viewer viewer) {
        return cohabits.archived(viewer);
    }

    @PostMapping("/cohabits")
    public ResponseEntity<CohabitDetail> create(Viewer viewer, @RequestBody CohabitInput input) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cohabits.create(viewer, input));
    }

    @GetMapping("/cohabits/{id}")
    public CohabitDetail get(Viewer viewer, @PathVariable("id") String id) {
        return cohabits.get(viewer, id);
    }

    @PutMapping("/cohabits/{id}")
    public CohabitDetail update(Viewer viewer, @PathVariable("id") String id, @RequestBody CohabitInput input) {
        return cohabits.update(viewer, id, input);
    }

    @PostMapping("/cohabits/{id}/archive")
    public CohabitDetail archive(Viewer viewer, @PathVariable("id") String id) {
        return cohabits.archive(viewer, id, true);
    }

    @PostMapping("/cohabits/{id}/unarchive")
    public CohabitDetail unarchive(Viewer viewer, @PathVariable("id") String id) {
        return cohabits.archive(viewer, id, false);
    }

    @DeleteMapping("/cohabits/{id}")
    public ResponseEntity<Void> delete(Viewer viewer, @PathVariable("id") String id,
                                       @RequestBody(required = false) ConfirmRequest request) {
        cohabits.delete(viewer, id, request != null && Boolean.TRUE.equals(request.confirm()));
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/cohabits/{id}/invite-candidates")
    public InviteCandidates candidates(Viewer viewer, @PathVariable("id") String id) {
        return cohabits.candidates(viewer, id);
    }

    @PostMapping("/cohabits/{id}/invitations")
    public InviteCandidates invite(Viewer viewer, @PathVariable("id") String id,
                                   @RequestBody PersonIdsRequest request) {
        return cohabits.invite(viewer, id, request.personIds());
    }

    @PostMapping("/cohabits/{id}/invite-link")
    public LinkView inviteLink(Viewer viewer, @PathVariable("id") String id) {
        return cohabits.inviteLink(viewer, id);
    }

    @DeleteMapping("/cohabits/{id}/members/{personId}")
    public ResponseEntity<Void> removeMember(Viewer viewer, @PathVariable("id") String id,
                                             @PathVariable("personId") String personId) {
        cohabits.removeMember(viewer, id, personId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/cohabits/{id}/admin")
    public CohabitDetail admin(Viewer viewer, @PathVariable("id") String id, @RequestBody PersonRequest request) {
        return cohabits.transferAdmin(viewer, id, request.personId());
    }

    @PutMapping("/cohabits/{id}/settings/me")
    public CohabitDetail settings(Viewer viewer, @PathVariable("id") String id,
                                  @RequestBody Map<String, Object> body) {
        return cohabits.updateMySettings(viewer, id, body);
    }

    @PostMapping("/cohabits/{id}/pauses")
    public CohabitDetail addPause(Viewer viewer, @PathVariable("id") String id, @RequestBody PauseRequest request) {
        return cohabits.addPause(viewer, id, request.from(), request.to());
    }

    @DeleteMapping("/cohabits/{id}/pauses/{pauseId}")
    public CohabitDetail deletePause(Viewer viewer, @PathVariable("id") String id,
                                     @PathVariable("pauseId") String pauseId) {
        return cohabits.deletePause(viewer, id, pauseId);
    }

    @PostMapping("/cohabits/{id}/dialogs/{dialogId}/seen")
    public ResponseEntity<Void> dialogSeen(Viewer viewer, @PathVariable("id") String id,
                                           @PathVariable("dialogId") String dialogId) {
        cohabits.dialogSeen(viewer, id, dialogId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/me/invitations")
    public List<InvitationView> invitations(Viewer viewer) {
        return cohabits.myInvitations(viewer);
    }

    @PostMapping("/invitations/{id}/accept")
    public CohabitDetail accept(Viewer viewer, @PathVariable("id") String id) {
        return cohabits.acceptInvitation(viewer, id);
    }

    @PostMapping("/invitations/{id}/decline")
    public ResponseEntity<Void> decline(Viewer viewer, @PathVariable("id") String id) {
        cohabits.declineInvitation(viewer, id);
        return ResponseEntity.noContent().build();
    }
}
