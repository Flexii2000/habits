package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.release.AndroidRelease;
import com.fherrmann.habits.cohabit.service.AccountService;
import com.fherrmann.habits.cohabit.service.Errors;
import com.fherrmann.habits.security.AuthCookies;
import com.fherrmann.habits.security.AuthVia;
import com.fherrmann.habits.security.Viewer;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.LocalDate;

/** Export, Account loeschen und die Android-App zum Herunterladen. */
@RestController
@RequestMapping("/cohabit/api")
public class AccountController {

    private static final MediaType APK = MediaType.parseMediaType("application/vnd.android.package-archive");

    public record ConfirmRequest(String confirm) {
    }

    private final AccountService account;
    private final AndroidRelease release;
    private final Clock clock;

    public AccountController(AccountService account, AndroidRelease release, Clock clock) {
        this.account = account;
        this.release = release;
        this.clock = clock;
    }

    @GetMapping("/me/export")
    public ResponseEntity<byte[]> export(Viewer viewer) {
        byte[] zip = account.export(viewer);
        String name = "cohabit-export-" + LocalDate.now(clock) + ".zip";
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(name).build().toString())
                .body(zip);
    }

    @DeleteMapping("/me")
    public ResponseEntity<Void> delete(Viewer viewer, @RequestBody(required = false) ConfirmRequest request,
                                       HttpServletRequest http) {
        account.delete(viewer, request == null ? null : request.confirm());
        ResponseEntity.HeadersBuilder<?> response = ResponseEntity.noContent();
        if (viewer.via() == AuthVia.COOKIE_APP) {
            response.header(HttpHeaders.SET_COOKIE, AuthCookies.clearCohabit(http).toString());
        }
        return response.build();
    }

    @GetMapping("/app/android")
    public AndroidRelease.Info android() {
        return release.latest().orElseThrow(() -> Errors.notFound("Es ist keine Android-App veröffentlicht."));
    }

    @GetMapping("/app/android/apk")
    public ResponseEntity<Resource> apk() {
        AndroidRelease.Info info = release.latest()
                .orElseThrow(() -> Errors.notFound("Es ist keine Android-App veröffentlicht."));
        return ResponseEntity.ok()
                .contentType(APK)
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment()
                        .filename("cohabit-" + info.versionName() + ".apk").build().toString())
                .body(new FileSystemResource(release.apk()));
    }
}
