package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.dto.FocusCategoryView;
import com.fherrmann.habits.security.Viewer;
import com.fherrmann.habits.service.FocusService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Die Kategorien aus dem Wald der Fokus-App - zur Auswahl, wenn ein Fokus-Habit nur
 * nach einer Kategorie zaehlen soll. Die Baeume gehoeren der Eigentuemerin; alle
 * anderen haben die Quelle FOCUS nicht und bekommen eine leere Liste.
 */
@RestController
public class FocusCategoriesController {

    private final FocusService focus;

    public FocusCategoriesController(FocusService focus) {
        this.focus = focus;
    }

    @GetMapping("/cohabit/api/focus/categories")
    public List<FocusCategoryView> categories(Viewer viewer) {
        return viewer.owner() ? focus.categories() : List.of();
    }
}
