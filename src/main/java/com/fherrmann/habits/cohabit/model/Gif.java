package com.fherrmann.habits.cohabit.model;

/**
 * Ein GIF aus der Suche (KLIPY). Gespeichert werden nur die Adressen - die Medien
 * laden die Geraete direkt von KLIPY, so verlangen es deren Bedingungen.
 */
public class Gif {
    public String provider;
    public String slug;
    public String title;
    public int width;
    public int height;
    public String gifUrl;
    public String webpUrl;
    public String mp4Url;
    public String stillUrl;
}
