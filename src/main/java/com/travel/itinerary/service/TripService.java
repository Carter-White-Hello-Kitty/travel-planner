package com.travel.itinerary.service;

import com.travel.auth.service.TokenService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import com.travel.itinerary.vo.TripCheckInResult;
import com.travel.itinerary.vo.TripSummary;
import com.travel.itinerary.vo.SaveTripRequest;
import com.travel.itinerary.vo.SavedTripResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/** Business operations on trips; callers pass the authenticated identity, never a client user id. */
public interface TripService {

    /** Plans from either a city area or required places, then replaces one Planning trip. */
    GenerationOutcome generate(TokenService.Identity identity, Long tripId, String requestId,
            @NotNull @Valid GenerateTripRequest request);

    /** Creates an empty Planning trip or replaces the complete editable plan. */
    SavedTripResult save(TokenService.Identity identity, Long tripId, @Valid SaveTripRequest request, boolean create);

    /** PROCESSING has no generated result yet; completed requests return the saved trip. */
    record GenerationOutcome(GeneratedTrip trip, boolean processing) {}

    /** Lists only the authenticated user's trips without loading days or POIs. */
    List<TripSummary> list(TokenService.Identity identity);

    /** Returns the caller's saved trip with current statuses and Google display fields. */
    GeneratedTrip detail(TokenService.Identity identity, Long tripId);

    /** Planning → Ongoing. */
    void start(TokenService.Identity identity, Long tripId);

    /** Ongoing → Cancelled. */
    void cancel(TokenService.Identity identity, Long tripId);

    /** Deletes a Planning trip with its days, POIs and generation record. */
    void delete(TokenService.Identity identity, Long tripId);

    /** Marks one POI visited; the last visit completes the trip. */
    TripCheckInResult checkIn(TokenService.Identity identity, Long tripPoiId);
}
