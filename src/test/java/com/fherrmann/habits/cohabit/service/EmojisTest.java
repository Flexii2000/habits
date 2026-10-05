package com.fherrmann.habits.cohabit.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class EmojisTest {

    @Test
    void einzelneEmojisAllerArtGehenDurch() {
        assertEquals("💪", Emojis.normalize("💪"));
        assertEquals("👍🏽", Emojis.normalize("👍🏽"), "Hautton");
        assertEquals("👨‍👩‍👧‍👦", Emojis.normalize("👨‍👩‍👧‍👦"), "ZWJ-Folge");
        assertEquals("🏳️‍🌈", Emojis.normalize("🏳️‍🌈"));
        assertEquals("🇩🇪", Emojis.normalize("🇩🇪"), "Flaggenpaar");
        assertEquals("🏴󠁧󠁢󠁳󠁣󠁴󠁿", Emojis.normalize("🏴󠁧󠁢󠁳󠁣󠁴󠁿"), "Regionalflagge mit Tag-Zeichen");
        assertEquals("1️⃣", Emojis.normalize("1️⃣"), "Tastenkappe");
        assertEquals("#⃣", Emojis.normalize("#⃣"));
        assertEquals("💪", Emojis.normalize("  💪 "));
    }

    @Test
    void einZeichenOhneEmojiDarstellungBekommtSie() {
        assertEquals("❤️", Emojis.normalize("❤"), "❤ und ❤️ sind dieselbe Reaktion");
        assertEquals("❤️", Emojis.normalize("❤️"));
        assertEquals("❤️", Emojis.normalize("❤︎"), "die Text-Darstellung faellt weg");
        assertEquals("☀️", Emojis.normalize("☀"));
    }

    @Test
    void dieAltenNamenWerdenZuEmojis() {
        assertEquals("💪", Emojis.normalize("STARK"));
        assertEquals("💪", Emojis.normalize("stark"));
        assertEquals("🙌", Emojis.normalize("RESPEKT"));
        assertEquals("🔥", Emojis.normalize("WEITER_SO"));
        assertEquals("😂", Emojis.normalize("HAHA"));
    }

    @Test
    void allesAndereIstKeinEmoji() {
        assertNull(Emojis.normalize(null));
        assertNull(Emojis.normalize(""));
        assertNull(Emojis.normalize("LIKE"));
        assertNull(Emojis.normalize("a"));
        assertNull(Emojis.normalize("1"), "eine Ziffer ohne Tastenkappe");
        assertNull(Emojis.normalize("#"));
        assertNull(Emojis.normalize("👍👍"), "zwei Emojis");
        assertNull(Emojis.normalize("👍 gut"));
        assertNull(Emojis.normalize("🇩"), "eine halbe Flagge");
        assertNull(Emojis.normalize("‍"));
        assertNull(Emojis.normalize("a⃣"), "Tastenkappe auf einem Buchstaben");
    }
}
