package com.fherrmann.habits.cohabit.model;

import com.fasterxml.jackson.annotation.JsonIgnore;

import java.time.Instant;

/**
 * Ein hochgeladenes Foto. {@code cohabitId} bzw. {@code avatarOf} sagen, wo es
 * verwendet wird - daran haengt, wer es sehen darf.
 */
public class PhotoMeta {
    public String id;
    public String ownerId;
    public String idempotencyKey;
    public Instant createdAt;
    public int width;
    public int height;
    public String cohabitId;
    public String avatarOf;

    @JsonIgnore
    public boolean isUsed() {
        return cohabitId != null || avatarOf != null;
    }
}
