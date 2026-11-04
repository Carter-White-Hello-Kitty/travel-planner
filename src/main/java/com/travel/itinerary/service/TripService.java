package com.travel.itinerary.service;

import com.travel.auth.service.TokenService;

/** Business operations on trips; callers pass the authenticated identity, never a client user id. */
public interface TripService {

    /** Planning → Ongoing. */
    void start(TokenService.Identity identity, Long tripId);

    /** Ongoing → Cancelled. */
    void cancel(TokenService.Identity identity, Long tripId);
}
