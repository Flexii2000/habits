package com.fherrmann.habits.cohabit.web;

import com.fherrmann.habits.cohabit.api.MeView;
import com.fherrmann.habits.cohabit.service.Errors;
import com.fherrmann.habits.cohabit.service.PhotoService;
import com.fherrmann.habits.security.Viewer;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Duration;

/** Fotos hochladen und ausliefern, dazu der eigene Avatar. */
@RestController
@RequestMapping("/cohabit/api")
public class PhotoController {

    private final PhotoService photos;

    public PhotoController(PhotoService photos) {
        this.photos = photos;
    }

    /** 201 fuer ein neues Foto, 200 fuer einen schon bekannten Idempotency-Key. */
    @PostMapping(value = "/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<PhotoService.Uploaded> upload(Viewer viewer, @RequestPart("photo") MultipartFile photo,
                                                        @RequestHeader(name = "Idempotency-Key", required = false)
                                                        String key) {
        PhotoService.Result result = photos.upload(viewer, bytes(photo), photo.getContentType(), key);
        return ResponseEntity.status(result.created() ? HttpStatus.CREATED : HttpStatus.OK).body(result.photo());
    }

    /** Einmal geladen, nie wieder: ein Foto aendert sich unter seiner ID nicht. */
    @GetMapping("/photos/{id}")
    public ResponseEntity<byte[]> read(Viewer viewer, @PathVariable("id") String id,
                                       @RequestParam(name = "size", required = false) String size) {
        PhotoService.Image image = photos.read(viewer, id, size);
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(365)).cachePrivate().immutable())
                .body(image.bytes());
    }

    @PutMapping(value = "/me/avatar", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public MeView setAvatar(Viewer viewer, @RequestPart("photo") MultipartFile photo) {
        return photos.setAvatar(viewer, bytes(photo), photo.getContentType());
    }

    @DeleteMapping("/me/avatar")
    public MeView removeAvatar(Viewer viewer) {
        return photos.removeAvatar(viewer);
    }

    private static byte[] bytes(MultipartFile photo) {
        if (photo == null || photo.isEmpty()) {
            throw Errors.badRequest("Das Foto fehlt.");
        }
        if (photo.getSize() > PhotoService.MAX_BYTES) {
            throw new org.springframework.web.server.ResponseStatusException(HttpStatus.CONTENT_TOO_LARGE,
                    "Das Foto ist zu groß (höchstens 10 MB).");
        }
        try {
            return photo.getBytes();
        } catch (IOException e) {
            throw Errors.badRequest("Das Foto kam nicht vollständig an.");
        }
    }
}
