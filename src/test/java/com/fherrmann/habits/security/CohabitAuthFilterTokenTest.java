package com.fherrmann.habits.security;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** Fehlt FH_PRIVATE_TOKEN auf dem Server, darf der Platzhalter aus dem Repo niemanden zur Eigentuemerin machen. */
class CohabitAuthFilterTokenTest {

    @Test
    void thePlaceholderIsNeverAnOwnerToken() {
        assertNull(CohabitAuthFilter.effectivePrivateToken(CohabitAuthFilter.PLACEHOLDER_TOKEN));
        assertNull(CohabitAuthFilter.effectivePrivateToken(""));
        assertNull(CohabitAuthFilter.effectivePrivateToken(null));
    }

    @Test
    void aRealTokenStays() {
        assertEquals("0123456789abcdef0123456789abcdef0123456789abcdef",
                CohabitAuthFilter.effectivePrivateToken("0123456789abcdef0123456789abcdef0123456789abcdef"));
    }
}
