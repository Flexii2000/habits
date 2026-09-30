package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.AcceptResult;
import com.fherrmann.habits.cohabit.api.InviteLinkPreview;
import com.fherrmann.habits.cohabit.api.LinkView;
import com.fherrmann.habits.cohabit.model.Cohabit;
import com.fherrmann.habits.cohabit.model.InviteLink;
import com.fherrmann.habits.cohabit.model.InviteLinkKind;
import com.fherrmann.habits.cohabit.model.Person;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import com.fherrmann.habits.security.AuthVia;
import com.fherrmann.habits.security.Viewer;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

/**
 * Einladungslinks: Vorschau ohne Anmeldung, und Annehmen - wer noch keinen Zugang
 * hat, wird dabei angelegt (Einladung = Registrierung) und bekommt einen Token.
 */
@Service
public class InviteLinkService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** Was beim Annehmen herauskommt; {@code token} nur, wenn eine Person neu entstand. */
    public record Accepted(AcceptResult result, String token) {
    }

    public record Registration(String displayName, String username, Boolean acceptTerms) {
    }

    private final CohabitStore store;
    private final CohabitService cohabits;
    private final PeopleService people;
    private final InviteLinks links;
    private final ViewService views;

    public InviteLinkService(CohabitStore store, CohabitService cohabits, PeopleService people, InviteLinks links,
                             ViewService views) {
        this.store = store;
        this.cohabits = cohabits;
        this.people = people;
        this.links = links;
        this.views = views;
    }

    public LinkView friendLink(Viewer viewer) {
        Instant now = views.now();
        return store.write(tx -> {
            PeopleService.requirePerson(tx, viewer.personId());
            return links.create(tx, InviteLinkKind.FRIEND, null, viewer.personId(), now);
        });
    }

    private static Optional<InviteLink> valid(CohabitStore.Data data, String code, Instant now) {
        if (!InviteLinks.isValidCode(code)) {
            return Optional.empty();
        }
        return data.cohabits().inviteLinks.stream()
                .filter(l -> l.code.equals(code) && l.expiresAt.isAfter(now))
                .filter(l -> data.person(l.createdBy).isPresent())
                .filter(l -> l.kind == InviteLinkKind.FRIEND
                        || data.cohabit(l.cohabitId).map(c -> !c.archived).orElse(false))
                .findFirst();
    }

    public InviteLinkPreview preview(String code) {
        Instant now = views.now();
        return store.read(data -> {
            InviteLink link = valid(data, code, now).orElseThrow(() -> Errors.notFound("Der Link ist ungültig oder abgelaufen."));
            if (link.kind == InviteLinkKind.FRIEND) {
                return new InviteLinkPreview("FRIEND", Views.person(data, link.createdBy), null, false);
            }
            Cohabit c = data.cohabit(link.cohabitId).orElseThrow();
            LocalDate today = CohabitService.today(c, now);
            return new InviteLinkPreview("COHABIT", Views.person(data, link.createdBy),
                    Views.inviteCohabit(data, c, today), Views.seatsUsed(data, c) >= Views.MAX_SEATS);
        });
    }

    /**
     * @param viewer   wer angemeldet ist, sonst {@code null} - dann legt {@code registration}
     *                 die Person an
     */
    public Accepted accept(String code, Viewer viewer, Registration registration) {
        Instant now = views.now();
        String[] issuedToken = new String[1];
        Viewer[] acting = new Viewer[]{viewer};
        String cohabitId = store.write(tx -> {
            InviteLink link = valid(tx, code, now).orElseThrow(() -> Errors.notFound("Der Link ist ungültig oder abgelaufen."));
            Cohabit c = link.kind == InviteLinkKind.COHABIT ? tx.cohabit(link.cohabitId).orElseThrow() : null;
            String viewerId = viewer == null ? null : viewer.personId();
            if (viewerId != null && Social.blockedEitherWay(tx, viewerId, link.createdBy)) {
                throw Errors.notFound("Der Link ist ungültig oder abgelaufen.");
            }
            boolean alreadyMember = c != null && viewerId != null && c.isMember(viewerId);
            if (c != null && !alreadyMember) {
                boolean holdsInvitation = viewerId != null && tx.cohabits().invitations.stream()
                        .anyMatch(i -> i.cohabitId.equals(c.id) && i.toId.equals(viewerId));
                if (!holdsInvitation && Views.seatsUsed(tx, c) >= Views.MAX_SEATS) {
                    throw Errors.conflict("Alle Plätze sind belegt.");
                }
            }
            String me = viewerId;
            if (me == null) {
                Person p = register(tx, registration, now);
                me = p.id;
                AppTokens.Issued issued = AppTokens.issue(p, "Beitritt", now);
                issuedToken[0] = issued.secret();
                acting[0] = new Viewer(p.id, AuthVia.COOKIE_APP, issued.token().id, false);
            }
            tx.cohabitsW();
            link.uses++;
            if (c == null) {
                Social.befriend(tx, me, link.createdBy, now);
                return null;
            }
            if (!alreadyMember) {
                cohabits.join(tx, c, me, link.createdBy, now);
            }
            return c.id;
        });
        String token = issuedToken[0];
        AcceptResult result = new AcceptResult(people.me(acting[0]), token,
                token == null ? null : people.setupUrl(token), cohabitId);
        return new Accepted(result, token);
    }

    private static Person register(CohabitStore.Tx tx, Registration r, Instant now) {
        if (r == null || !Boolean.TRUE.equals(r.acceptTerms())) {
            throw Errors.badRequest("Bitte Nutzungsbedingungen und Datenschutzerklärung akzeptieren.");
        }
        String name = r.displayName() == null ? "" : r.displayName().trim().replaceAll("\\s+", " ");
        if (name.isEmpty() || name.length() > Persons.MAX_DISPLAY_NAME) {
            throw Errors.badRequest("Der Anzeigename braucht 1 bis 30 Zeichen.");
        }
        String username = r.username() == null ? "" : r.username().trim().toLowerCase(Locale.ROOT).replaceFirst("^@", "");
        if (!Persons.USERNAME.matcher(username).matches()) {
            throw Errors.badRequest("Der Nutzername braucht 3 bis 20 Zeichen (a–z, 0–9, Punkt, Unterstrich) "
                    + "und beginnt mit einem Buchstaben.");
        }
        if (Persons.isUsernameTaken(tx, username, null)) {
            throw Errors.conflict("Der Nutzername ist schon vergeben.");
        }
        String id;
        do {
            byte[] bytes = new byte[6];
            RANDOM.nextBytes(bytes);
            id = "u-" + HexFormat.of().formatHex(bytes);
        } while (tx.person(id).isPresent());
        return Persons.create(tx, id, name, username, now);
    }
}
