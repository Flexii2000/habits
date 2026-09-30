package com.fherrmann.habits.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.multipart.MultipartException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.util.Map;

/**
 * Fehler: unter {@code /cohabit/} als JSON {@code {"message":"..."}} (alle
 * coHabit-Clients zeigen {@code message}), sonst als Klartext wie bisher - die
 * Fokus-App zeigt beim Wald den Rumpf einer Fehlerantwort direkt an.
 */
@RestControllerAdvice
public class ErrorAdvice {

    private static final Logger log = LoggerFactory.getLogger(ErrorAdvice.class);

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<?> handle(ResponseStatusException e, HttpServletRequest request) {
        String reason = e.getReason();
        if (reason == null) {
            HttpStatus status = HttpStatus.resolve(e.getStatusCode().value());
            reason = status == null ? e.getStatusCode().toString() : status.getReasonPhrase();
        }
        return respond(request, e.getStatusCode(), reason);
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    ResponseEntity<?> tooLarge(MaxUploadSizeExceededException e, HttpServletRequest request) {
        return respond(request, HttpStatus.CONTENT_TOO_LARGE, "Das Foto ist zu groß (höchstens 10 MB).");
    }

    @ExceptionHandler({HttpMessageNotReadableException.class, MissingServletRequestParameterException.class,
            MethodArgumentTypeMismatchException.class, MissingServletRequestPartException.class,
            MissingRequestHeaderException.class, MultipartException.class})
    ResponseEntity<?> badInput(Exception e, HttpServletRequest request) {
        return respond(request, HttpStatus.BAD_REQUEST, "Ungültige Eingabe.");
    }

    @ExceptionHandler(NoResourceFoundException.class)
    ResponseEntity<?> notFound(NoResourceFoundException e, HttpServletRequest request) {
        return respond(request, HttpStatus.NOT_FOUND, "Nicht gefunden.");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    ResponseEntity<?> method(HttpRequestMethodNotSupportedException e, HttpServletRequest request) {
        return respond(request, HttpStatus.METHOD_NOT_ALLOWED, "Diese Methode gibt es hier nicht.");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    ResponseEntity<?> mediaType(HttpMediaTypeNotSupportedException e, HttpServletRequest request) {
        return respond(request, HttpStatus.UNSUPPORTED_MEDIA_TYPE, "Dieses Format wird nicht angenommen.");
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<?> unexpected(Exception e, HttpServletRequest request) {
        log.error("Unerwarteter Fehler bei {} {}", request.getMethod(), request.getRequestURI(), e);
        return respond(request, HttpStatus.INTERNAL_SERVER_ERROR, "Da ist etwas schiefgegangen.");
    }

    public static boolean isCohabitPath(HttpServletRequest request) {
        String uri = request.getRequestURI();
        return uri != null && (uri.equals("/cohabit") || uri.startsWith("/cohabit/"));
    }

    private static ResponseEntity<?> respond(HttpServletRequest request, HttpStatusCode status, String message) {
        if (isCohabitPath(request)) {
            return ResponseEntity.status(status).contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("message", message));
        }
        return ResponseEntity.status(status)
                .header("Content-Type", "text/plain;charset=UTF-8")
                .body(message);
    }
}
