package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.GifConfig;
import com.fherrmann.habits.cohabit.service.GifService;
import com.fherrmann.habits.security.Viewer;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/** Was die Apps fuer die GIF-Suche bei KLIPY brauchen. */
@RestController
public class GifController {

    private final GifService gifs;

    public GifController(GifService gifs) {
        this.gifs = gifs;
    }

    @GetMapping("/cohabit/api/gifs/config")
    public GifConfig config(Viewer viewer) {
        return gifs.config(viewer);
    }
}
