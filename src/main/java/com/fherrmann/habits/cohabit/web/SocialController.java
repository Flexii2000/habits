package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.MessagePage;
import com.fherrmann.habits.cohabit.api.MessageView;
import com.fherrmann.habits.cohabit.api.NudgeView;
import com.fherrmann.habits.cohabit.api.ReactionsView;
import com.fherrmann.habits.cohabit.api.TimelinePage;
import com.fherrmann.habits.cohabit.service.ChatService;
import com.fherrmann.habits.cohabit.service.NudgeService;
import com.fherrmann.habits.cohabit.service.ReactionService;
import com.fherrmann.habits.cohabit.service.TimelineService;
import com.fherrmann.habits.security.Viewer;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Chat, Reaktionen, Timeline und Stupser. */
@RestController
@RequestMapping("/cohabit/api")
public class SocialController {

    public record ReasonRequest(String reason) {
    }

    public record ReadRequest(String lastMessageId) {
    }

    public record ReactionRequest(String target, String reaction) {
    }

    public record SeenRequest(String lastEventId) {
    }

    public record NudgeRequest(String to, String text) {
    }

    private final ChatService chat;
    private final ReactionService reactions;
    private final TimelineService timeline;
    private final NudgeService nudges;

    public SocialController(ChatService chat, ReactionService reactions, TimelineService timeline,
                            NudgeService nudges) {
        this.chat = chat;
        this.reactions = reactions;
        this.timeline = timeline;
        this.nudges = nudges;
    }

    @GetMapping("/cohabits/{id}/messages")
    public MessagePage messages(Viewer viewer, @PathVariable("id") String id,
                                @RequestParam(name = "before", required = false) String before,
                                @RequestParam(name = "after", required = false) String after,
                                @RequestParam(name = "limit", required = false) Integer limit) {
        return chat.page(viewer, id, before, after, limit);
    }

    /** 201 fuer eine neue Nachricht, 200 fuer eine schon bekannte ID. */
    @PostMapping("/cohabits/{id}/messages")
    public ResponseEntity<MessageView> send(Viewer viewer, @PathVariable("id") String id,
                                            @RequestBody ChatService.SendInput input) {
        ChatService.Sent sent = chat.send(viewer, id, input);
        return ResponseEntity.status(sent.created() ? HttpStatus.CREATED : HttpStatus.OK).body(sent.message());
    }

    @DeleteMapping("/cohabits/{id}/messages/{messageId}")
    public MessageView delete(Viewer viewer, @PathVariable("id") String id,
                              @PathVariable("messageId") String messageId) {
        return chat.delete(viewer, id, messageId);
    }

    @PostMapping("/cohabits/{id}/messages/{messageId}/report")
    public ResponseEntity<Void> report(Viewer viewer, @PathVariable("id") String id,
                                       @PathVariable("messageId") String messageId,
                                       @RequestBody(required = false) ReasonRequest request) {
        chat.report(viewer, id, messageId, request == null ? null : request.reason());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/cohabits/{id}/read")
    public Map<String, Integer> read(Viewer viewer, @PathVariable("id") String id, @RequestBody ReadRequest request) {
        return Map.of("unread", chat.read(viewer, id, request.lastMessageId()));
    }

    @PostMapping("/reactions")
    public ReactionsView react(Viewer viewer, @RequestBody ReactionRequest request) {
        return reactions.add(viewer, request.target(), request.reaction());
    }

    @DeleteMapping("/reactions")
    public ReactionsView unreact(Viewer viewer, @RequestParam("target") String target,
                                 @RequestParam(name = "reaction", required = false) String reaction) {
        return reactions.remove(viewer, target, reaction);
    }

    /**
     * {@code exclude}: Co-Habits, die der Filter der Clients ausblendet - kommagetrennt
     * oder mehrfach. Ausblenden statt Auswaehlen, damit neue Co-Habits von selbst
     * erscheinen; unbekannte IDs (inzwischen geloescht) stoeren nicht.
     */
    @GetMapping("/timeline")
    public TimelinePage timeline(Viewer viewer, @RequestParam(name = "cohabitId", required = false) String cohabitId,
                                 @RequestParam(name = "exclude", required = false) List<String> exclude,
                                 @RequestParam(name = "before", required = false) String before,
                                 @RequestParam(name = "limit", required = false) Integer limit) {
        return timeline.page(viewer, cohabitId, exclude == null ? Set.of() : Set.copyOf(exclude), before, limit);
    }

    @PostMapping("/timeline/seen")
    public ResponseEntity<Void> timelineSeen(Viewer viewer, @RequestBody SeenRequest request) {
        timeline.seen(viewer, request.lastEventId());
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/cohabits/{id}/nudges")
    public ResponseEntity<NudgeView> nudge(Viewer viewer, @PathVariable("id") String id,
                                           @RequestBody NudgeRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(nudges.nudge(viewer, id, request.to(), request.text()));
    }

    @PostMapping("/nudges/{id}/seen")
    public ResponseEntity<Void> nudgeSeen(Viewer viewer, @PathVariable("id") String id) {
        nudges.seen(viewer, id);
        return ResponseEntity.noContent().build();
    }
}
