package com.travel.itinerary.service;

import com.travel.auth.repository.UserRepository;
import com.travel.auth.security.Ownership;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.itinerary.entity.*;
import com.travel.itinerary.repository.*;
import com.travel.itinerary.vo.SaveTripRequest;
import com.travel.poisearch.client.GooglePlacesClient;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ManualTripService {
    private final TripRepository trips;
    private final TripDayRepository days;
    private final TripPoiRepository pois;
    private final UserRepository users;
    private final GooglePlacesClient google;
    public ManualTripService(TripRepository trips, TripDayRepository days, TripPoiRepository pois,
            UserRepository users, GooglePlacesClient google) {
        this.trips = trips; this.days = days; this.pois = pois; this.users = users; this.google = google;
    }

    @Transactional
    public Long save(TokenService.Identity identity, Long id, SaveTripRequest request) {
        if (identity == null) throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        Trip trip;
        if (id == null) {
            trip = new Trip();
            trip.setUser(users.getReferenceById(identity.userId()));
            trip.setStatus(TripStatus.PLANNING);
        } else {
            if (id < 1) throw ApiError.invalid("id", "Must be a positive trip ID");
            trip = trips.findByIdForUpdate(id).orElseThrow(() ->
                    new ApiError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Trip not found"));
            Ownership.requireOwner(identity, trip.getUser().getId());
            if (trip.getStatus() != TripStatus.PLANNING)
                throw new ApiError(HttpStatus.CONFLICT, "INVALID_TRIP_STATUS", "Only Planning trips can be edited");
        }
        List<TripDay> oldDays = id == null ? new ArrayList<>() : days.findByTripIdOrderByDayNumberAsc(id);
        Map<String, TripPoi> oldPois = new HashMap<>();
        for (TripDay day : oldDays)
            for (TripPoi poi : pois.findByTripDayIdOrderBySequenceAsc(day.getId()))
                oldPois.put(poi.getGooglePlaceId(), poi);
        // Validate all new place IDs before changing persisted rows. Any failure rolls back the plan.
        for (List<String> day : request.days()) for (String placeId : day) {
            if (!oldPois.containsKey(placeId)) {
                var place = google.details(placeId);
                if (place == null || !placeId.equals(place.googlePlaceId()))
                    throw ApiError.invalid("days", "Invalid Google place");
            }
        }
        trip.setName(request.name().strip());
        trip.setDestination(request.destination().strip());
        trip.setStartDate(request.startDate());
        trip.setDayCount(request.days().size());
        trips.saveAndFlush(trip);
        for (int i = 0; i < request.days().size(); i++) {
            TripDay day;
            if (i < oldDays.size()) day = oldDays.get(i);
            else {
                day = new TripDay(); day.setTrip(trip); day.setDayNumber(i + 1);
                days.saveAndFlush(day);
            }
            for (int sequence = 0; sequence < request.days().get(i).size(); sequence++) {
                String placeId = request.days().get(i).get(sequence);
                TripPoi poi = oldPois.remove(placeId);
                if (poi == null) { poi = new TripPoi(); poi.setGooglePlaceId(placeId); }
                poi.setTripDay(day); poi.setSequence(sequence + 1);
                pois.save(poi);
            }
        }
        pois.deleteAll(oldPois.values());
        pois.flush();
        if (oldDays.size() > request.days().size()) days.deleteAll(oldDays.subList(request.days().size(), oldDays.size()));
        return trip.getId();
    }
}
