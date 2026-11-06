package com.travel.itinerary.service;

import com.travel.auth.service.TokenService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import com.travel.itinerary.vo.TripSummary;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Business operations on trips; callers pass the authenticated identity, never a client user id. */
public interface TripService {

    /** Plans from either a city area or required places, then saves one Planning trip. */
    GeneratedTrip generate(TokenService.Identity identity, @NotNull @Valid GenerateTripRequest request);

    /** Lists only the authenticated user's trips without loading days or POIs. */
    List<TripSummary> list(TokenService.Identity identity);

    /** Planning → Ongoing. */
    void start(TokenService.Identity identity, Long tripId);

    /** Ongoing → Cancelled. */
    void cancel(TokenService.Identity identity, Long tripId);
}
