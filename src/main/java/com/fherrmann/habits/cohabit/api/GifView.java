package com.fherrmann.habits.cohabit.api;

import com.fherrmann.habits.cohabit.model.Gif;

/** Ein GIF im Chat: die Clients waehlen die Fassung, die sie am besten abspielen. */
public record GifView(String provider, String slug, String title, int width, int height, String gifUrl,
                      String webpUrl, String mp4Url, String stillUrl) {

    public static GifView of(Gif gif) {
        return gif == null ? null : new GifView(gif.provider, gif.slug, gif.title, gif.width, gif.height, gif.gifUrl,
                gif.webpUrl, gif.mp4Url, gif.stillUrl);
    }
}
