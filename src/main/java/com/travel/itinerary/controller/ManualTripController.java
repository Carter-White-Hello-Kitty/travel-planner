package com.travel.itinerary.controller;

import com.travel.auth.service.TokenService;
import com.travel.common.Result;
import com.travel.itinerary.service.ManualTripService;
import com.travel.itinerary.vo.SaveTripRequest;
import jakarta.validation.Valid;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/trips")
public class ManualTripController {
    private final ManualTripService plans;
    public ManualTripController(ManualTripService plans) { this.plans = plans; }
    @PostMapping("/manual")
    @ResponseStatus(HttpStatus.CREATED)
    public Result<Map<String, Long>> create(@AuthenticationPrincipal TokenService.Identity identity,
            @Valid @RequestBody SaveTripRequest request) {
        return Result.success(Map.of("tripId", plans.save(identity, null, request)));
    }
    @PutMapping("/{id}/plan")
    public Result<Map<String, Long>> update(@AuthenticationPrincipal TokenService.Identity identity,
            @PathVariable Long id, @Valid @RequestBody SaveTripRequest request) {
        return Result.success(Map.of("tripId", plans.save(identity, id, request)));
    }
}
