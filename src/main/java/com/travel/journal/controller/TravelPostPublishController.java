package com.travel.journal.controller;

import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.common.Result;
import com.travel.journal.service.TravelPostPublishService;
import com.travel.journal.vo.CreateTravelPostRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/travel-posts")
public class TravelPostPublishController {
    private final TravelPostPublishService service;

    public TravelPostPublishController(TravelPostPublishService service) {
        this.service = service;
    }

    public record CreatedPost(Long id) {}

    @PostMapping
    public ResponseEntity<Result<CreatedPost>> publish(
            @AuthenticationPrincipal TokenService.Identity identity,
            @RequestBody CreateTravelPostRequest request) {
        if (identity == null) {
            throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        }
        Long id = service.publish(identity.userId(), request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(Result.success("Post created successfully.", new CreatedPost(id)));
    }
}
