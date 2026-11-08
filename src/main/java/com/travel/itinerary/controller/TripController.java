package com.travel.itinerary.controller;

import com.travel.auth.service.TokenService;
import com.travel.common.Result;
import com.travel.itinerary.service.TripService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import com.travel.itinerary.vo.TripCheckInResult;
import com.travel.itinerary.vo.TripSummary;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    @PostMapping
    public ResponseEntity<Result<GeneratedTrip>> generate(
            @AuthenticationPrincipal TokenService.Identity identity,
            @RequestHeader(value = "Idempotency-Key", required = false) String requestId,
            @Valid @RequestBody GenerateTripRequest request) {
        TripService.GenerationOutcome outcome = tripService.generate(identity, requestId, request);
        if (outcome.processing()) {
            return ResponseEntity.status(HttpStatus.ACCEPTED).header("Retry-After", "2")
                    .body(Result.success("Trip generation in progress", null));
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(Result.success(outcome.trip()));
    }

    @GetMapping
    public Result<List<TripSummary>> list(
            @AuthenticationPrincipal TokenService.Identity identity) {
        return Result.success(tripService.list(identity));
    }

    @GetMapping("/{id}")
    public Result<GeneratedTrip> detail(
            @AuthenticationPrincipal TokenService.Identity identity, @PathVariable Long id) {
        return Result.success(tripService.detail(identity, id));
    }

    @PostMapping("/{id}/start")
    public Result<Void> start(
            @AuthenticationPrincipal TokenService.Identity identity, @PathVariable Long id) {
        tripService.start(identity, id);
        return Result.success();
    }

    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(
            @AuthenticationPrincipal TokenService.Identity identity, @PathVariable Long id) {
        tripService.cancel(identity, id);
        return Result.success();
    }

    @PostMapping("/{tripPoiId}/check-in")
    public Result<TripCheckInResult> checkIn(
            @AuthenticationPrincipal TokenService.Identity identity, @PathVariable Long tripPoiId) {
        return Result.success(tripService.checkIn(identity, tripPoiId));
    }
}
