package com.travel.journal.controller;

import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.common.Result;
import com.travel.journal.service.TravelPostPhotoService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/travel-posts/photos")
public class TravelPostPhotoController {
    private final TravelPostPhotoService service;

    public TravelPostPhotoController(TravelPostPhotoService service) {
        this.service = service;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Result<UploadedPhoto>> upload(
            @AuthenticationPrincipal TokenService.Identity identity,
            @RequestParam("file") MultipartFile file) {
        if (identity == null) {
            throw new ApiError(
                    HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        }

        String url = service.upload(file);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success("Photo uploaded successfully.", new UploadedPhoto(url)));
    }

    public record UploadedPhoto(String url) {}
}