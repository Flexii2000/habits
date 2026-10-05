package com.fherrmann.habits.cohabit.model;

public enum MessageKind {
    TEXT, PHOTO,
    /** Ein GIF aus der Suche (KLIPY); eigene GIFs sind Fotos mit {@code photoAnimated}. */
    GIF,
    CHECKIN, SYSTEM
}
