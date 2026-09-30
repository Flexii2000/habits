package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.service.AppTokens;
import com.fherrmann.habits.security.AuthCookies;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

/**
 * {@code /cohabit/setup?token=<App-Token>}: richtet einen Browser ein (Cookie
 * {@code cohabit_token}) und leitet auf die Oberflaeche. Ein ungueltiger Link
 * landet auf {@code /cohabit/?setup=invalid} - dort zeigt die Seite "Link ungueltig".
 */
@RestController
public class SetupController {

    private final AppTokens appTokens;

    public SetupController(AppTokens appTokens) {
        this.appTokens = appTokens;
    }

    @GetMapping("/cohabit/setup")
    public ResponseEntity<Void> setup(@RequestParam(name = "token", required = false) String token,
                                      HttpServletRequest request) {
        if (token == null || appTokens.lookup(token.trim()).isEmpty()) {
            return ResponseEntity.status(HttpStatus.FOUND).location(URI.create("/cohabit/?setup=invalid")).build();
        }
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.SET_COOKIE, AuthCookies.cohabit(token.trim(), request).toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .location(URI.create("/cohabit/"))
                .build();
    }
}
