package com.travel.itinerary.controller;

import com.travel.auth.service.TokenService;
import com.travel.common.Result;
import com.travel.itinerary.service.TripService;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/trips")
public class TripController {

    private final TripService tripService;

    public TripController(TripService tripService) {
        this.tripService = tripService;
    }

    /** Planning → Ongoing. */
    @PostMapping("/{id}/start")
    public Result<Void> start(
            @AuthenticationPrincipal TokenService.Identity identity, @PathVariable Long id) {
        tripService.start(identity, id);
        return Result.success();
    }

    /** Ongoing → Cancelled. */
    @PostMapping("/{id}/cancel")
    public Result<Void> cancel(
            @AuthenticationPrincipal TokenService.Identity identity, @PathVariable Long id) {
        tripService.cancel(identity, id);
        return Result.success();
    }
}
