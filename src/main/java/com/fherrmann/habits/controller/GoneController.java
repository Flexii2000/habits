package com.fherrmann.habits.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * Die alte Habit-API gibt es nicht mehr: 410 mit dem Hinweis, wohin es ging. Eine
 * alte Fokus-App mit Habits-Tab zeigt damit einen Satz statt eines leeren Tabs.
 */
@RestController
public class GoneController {

    private final String message;

    public GoneController(@Value("${cohabit.public-url:https://fherrmann.com/cohabit}") String publicUrl) {
        this.message = "Die Habits sind nach coHabit umgezogen: " + publicUrl.replaceAll("/+$", "") + "/";
    }

    @RequestMapping({"/habits/api/habits", "/habits/api/habits/**"})
    public ResponseEntity<Map<String, String>> gone() {
        return ResponseEntity.status(HttpStatus.GONE).contentType(MediaType.APPLICATION_JSON)
                .body(Map.of("message", message));
    }
}
