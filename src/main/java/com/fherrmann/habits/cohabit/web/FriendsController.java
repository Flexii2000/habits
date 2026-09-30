package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.FriendRequestView;
import com.fherrmann.habits.cohabit.api.FriendsView;
import com.fherrmann.habits.cohabit.api.PersonView;
import com.fherrmann.habits.cohabit.api.SearchResult;
import com.fherrmann.habits.cohabit.service.FriendsService;
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

/** Freunde, Suche und Blocks. */
@RestController
@RequestMapping("/cohabit/api")
public class FriendsController {

    public record UsernameRequest(String username) {
    }

    public record PersonRequest(String personId) {
    }

    private final FriendsService friends;

    public FriendsController(FriendsService friends) {
        this.friends = friends;
    }

    @GetMapping("/friends")
    public FriendsView friends(Viewer viewer) {
        return friends.friends(viewer);
    }

    @GetMapping("/people/search")
    public List<SearchResult> search(Viewer viewer, @RequestParam(name = "q", required = false) String q) {
        return friends.search(viewer, q);
    }

    @PostMapping("/friends/requests")
    public ResponseEntity<FriendRequestView> request(Viewer viewer, @RequestBody UsernameRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(friends.request(viewer, request.username()));
    }

    @PostMapping("/friends/requests/{id}/accept")
    public FriendsView accept(Viewer viewer, @PathVariable("id") String id) {
        return friends.accept(viewer, id);
    }

    @PostMapping("/friends/requests/{id}/decline")
    public FriendsView decline(Viewer viewer, @PathVariable("id") String id) {
        return friends.decline(viewer, id);
    }

    @DeleteMapping("/friends/{personId}")
    public ResponseEntity<Void> unfriend(Viewer viewer, @PathVariable("personId") String personId) {
        friends.unfriend(viewer, personId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/blocks")
    public List<PersonView> blocks(Viewer viewer) {
        return friends.blocks(viewer);
    }

    @PostMapping("/blocks")
    public ResponseEntity<Void> block(Viewer viewer, @RequestBody PersonRequest request) {
        friends.block(viewer, request.personId());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/blocks/{personId}")
    public ResponseEntity<Void> unblock(Viewer viewer, @PathVariable("personId") String personId) {
        friends.unblock(viewer, personId);
        return ResponseEntity.noContent().build();
    }
}
