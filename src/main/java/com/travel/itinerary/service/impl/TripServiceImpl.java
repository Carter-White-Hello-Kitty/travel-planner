package com.travel.itinerary.service.impl;

import com.travel.auth.security.Ownership;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.itinerary.entity.Trip;
import com.travel.itinerary.entity.TripStatus;
import com.travel.itinerary.repository.TripRepository;
import com.travel.itinerary.service.TripService;
import com.travel.itinerary.vo.TripSummary;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TripServiceImpl implements TripService {

    private final TripRepository tripRepository;

    public TripServiceImpl(TripRepository tripRepository) {
        this.tripRepository = tripRepository;
    }

    @Override
    @Transactional(readOnly = true)
    public List<TripSummary> list(TokenService.Identity identity) {
        if (identity == null)
            throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        return tripRepository.findSummariesByUserId(identity.userId());
    }

    @Override
    @Transactional
    public void start(TokenService.Identity identity, Long tripId) {
        changeStatus(identity, tripId, TripStatus.ONGOING);
    }

    @Override
    @Transactional
    public void cancel(TokenService.Identity identity, Long tripId) {
        changeStatus(identity, tripId, TripStatus.CANCELLED);
    }

    /** Loads the caller's trip under a row lock and applies a legal status transition. */
    private void changeStatus(TokenService.Identity identity, Long tripId, TripStatus next) {
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Trip not found"));
        Ownership.requireOwner(identity, trip.getUser().getId());

        TripStatus current = TripStatus.from(trip.getStatus());
        if (!current.canTransitionTo(next)) {
            throw new ApiError(HttpStatus.CONFLICT, "INVALID_TRIP_STATUS",
                    "Cannot change trip from " + current.value() + " to " + next.value());
        }
        trip.setStatus(next.value());
        tripRepository.save(trip);
    }
}
