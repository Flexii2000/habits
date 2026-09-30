package com.fherrmann.habits.cohabit.service;

import com.fherrmann.habits.cohabit.api.LinkView;
import com.fherrmann.habits.cohabit.model.InviteLink;
import com.fherrmann.habits.cohabit.model.InviteLinkKind;
import com.fherrmann.habits.cohabit.store.CohabitStore;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;

/** Einladungs- und Freundes-Links: 22 Zeichen Base62, 14 Tage gueltig. */
@Component
public class InviteLinks {

    public static final Duration VALIDITY = Duration.ofDays(14);
    private static final String BASE62 = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz";
    private static final SecureRandom RANDOM = new SecureRandom();

    private final String publicUrl;

    public InviteLinks(@Value("${cohabit.public-url:https://fherrmann.com/cohabit}") String publicUrl) {
        this.publicUrl = publicUrl.replaceAll("/+$", "");
    }

    public static String newCode() {
        StringBuilder code = new StringBuilder(22);
        for (int i = 0; i < 22; i++) {
            code.append(BASE62.charAt(RANDOM.nextInt(BASE62.length())));
        }
        return code.toString();
    }

    public String url(String code) {
        return publicUrl + "/join/" + code;
    }

    public LinkView create(CohabitStore.Tx tx, InviteLinkKind kind, String cohabitId, String creatorId, Instant now) {
        InviteLink link = new InviteLink();
        link.code = newCode();
        link.kind = kind;
        link.cohabitId = cohabitId;
        link.createdBy = creatorId;
        link.createdAt = now;
        link.expiresAt = now.plus(VALIDITY);
        tx.cohabitsW().inviteLinks.add(link);
        return new LinkView(url(link.code), link.code, link.expiresAt);
    }

    public static boolean isValidCode(String code) {
        return code != null && code.matches("[0-9A-Za-z]{22}");
    }
}
