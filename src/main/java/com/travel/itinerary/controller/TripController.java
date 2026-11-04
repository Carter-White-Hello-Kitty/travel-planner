package com.travel.itinerary.controller;

import com.travel.auth.service.TokenService;
import com.travel.common.Result;
import com.travel.itinerary.service.TripService;
import com.travel.itinerary.vo.TripSummary;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
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

    @GetMapping
    public Result<List<TripSummary>> list(
            @AuthenticationPrincipal TokenService.Identity identity) {
        return Result.success(tripService.list(identity));
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
}
