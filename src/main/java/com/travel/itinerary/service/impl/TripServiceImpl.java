package com.travel.itinerary.service.impl;

import com.travel.auth.security.Ownership;
import com.travel.auth.entity.User;
import com.travel.auth.repository.UserRepository;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.itinerary.entity.Trip;
import com.travel.itinerary.entity.TripDay;
import com.travel.itinerary.entity.TripPoi;
import com.travel.itinerary.entity.TripPoiStatus;
import com.travel.itinerary.entity.TripStatus;
import com.travel.itinerary.repository.TripRepository;
import com.travel.itinerary.service.TripService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import com.travel.itinerary.vo.TripSummary;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.service.PoiSearchService;
import com.travel.poisearch.vo.PoiSearchRequest;
import com.travel.poisearch.vo.PoiVO;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;

@Service
@Validated
public class TripServiceImpl implements TripService {
    private static final int DEFAULT_RADIUS_METERS = 20_000;
    private static final int SEARCH_MARGIN_METERS = 5_000;
    private static final int MAX_RADIUS_METERS = 50_000;
    private static final double DISTANCE_PENALTY_PER_KM = 0.05;
    private static final double MIN_OPTIONAL_SCORE = 0.60;
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final TripRepository tripRepository;
    private final UserRepository userRepository;
    private final GooglePlacesClient google;
    private final PoiSearchService search;
    private final TransactionTemplate transactions;

    public TripServiceImpl(TripRepository tripRepository, UserRepository userRepository,
            GooglePlacesClient google, PoiSearchService search,
            PlatformTransactionManager transactionManager) {
        this.tripRepository = tripRepository;
        this.userRepository = userRepository;
        this.google = google;
        this.search = search;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    @Override
    public GeneratedTrip generate(TokenService.Identity identity, GenerateTripRequest request) {
        if (identity == null || identity.userId() == null)
            throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");

        List<List<PoiVO>> required = new ArrayList<>();
        for (int day = 0; day < request.dayCount(); day++) required.add(new ArrayList<>());
        Set<String> requiredIds = new HashSet<>();
        if (request.tripDays() != null) {
            for (GenerateTripRequest.Day day : request.tripDays()) {
                for (GenerateTripRequest.MustVisitPoi item : day.mustVisitPois()) {
                    PoiVO poi = google.details(item.googlePlaceId());
                    if (poi == null || !item.googlePlaceId().equals(poi.googlePlaceId()) || !usable(poi))
                        throw ApiError.invalid("mustVisitPois", "A required POI is unavailable");
                    required.get(day.dayNumber() - 1).add(poi);
                    requiredIds.add(poi.googlePlaceId());
                }
            }
        }

        GenerateTripRequest.SearchArea area = request.searchArea() != null
                ? request.searchArea() : areaAround(required);
        List<String> preferredTypes = request.constraints().preferredTypes() == null
                ? List.of() : request.constraints().preferredTypes();
        Map<String, PoiVO> candidates = new LinkedHashMap<>();
        List<String> queries = new ArrayList<>();
        queries.add("tourist attractions");
        queries.addAll(preferredTypes);
        for (String query : queries) {
            for (PoiVO poi : search.search(new PoiSearchRequest(
                    query, area.latitude(), area.longitude(), area.radiusMeters()))) {
                if (usable(poi) && !requiredIds.contains(poi.googlePlaceId()))
                    candidates.putIfAbsent(poi.googlePlaceId(), poi);
            }
        }

        List<List<PoiVO>> plan = plan(required, List.copyOf(candidates.values()),
                request.constraints().maxPoisPerDay(), preferredTypes);
        return transactions.execute(status -> savePlan(identity.userId(), request, plan));
    }

    private GeneratedTrip savePlan(Long userId, GenerateTripRequest request, List<List<PoiVO>> plan) {
        User owner = userRepository.findById(userId).orElseThrow(() ->
                new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required"));
        Trip trip = new Trip();
        trip.setUser(owner);
        trip.setName(request.name().strip());
        trip.setStartDate(request.startDate());
        trip.setDayCount(plan.size());
        trip.setStatus(TripStatus.PLANNING);
        for (int dayIndex = 0; dayIndex < plan.size(); dayIndex++) {
            TripDay day = new TripDay();
            day.setDayNumber(dayIndex + 1);
            trip.addDay(day);

            for (int position = 0; position < plan.get(dayIndex).size(); position++) {
                PoiVO info = plan.get(dayIndex).get(position);
                TripPoi tripPoi = new TripPoi();
                tripPoi.setGooglePlaceId(info.googlePlaceId());
                tripPoi.setSequence(position + 1);
                tripPoi.setStatus(TripPoiStatus.PLANNED);
                day.addPoi(tripPoi);
            }
        }

        tripRepository.save(trip);
        List<GeneratedTrip.Day> resultDays = new ArrayList<>();
        for (int dayIndex = 0; dayIndex < plan.size(); dayIndex++) {
            List<GeneratedTrip.Poi> resultPois = new ArrayList<>();
            List<TripPoi> savedPois = trip.getTripDays().get(dayIndex).getTripPois();
            for (int position = 0; position < savedPois.size(); position++) {
                TripPoi tripPoi = savedPois.get(position);
                PoiVO info = plan.get(dayIndex).get(position);
                resultPois.add(new GeneratedTrip.Poi(tripPoi.getId(), info.googlePlaceId(),
                        info.name(), info.address(), info.latitude(), info.longitude(),
                        tripPoi.getStatus().value()));
            }
            resultDays.add(new GeneratedTrip.Day(dayIndex + 1, List.copyOf(resultPois)));
        }
        return new GeneratedTrip(trip.getId(), trip.getName(), trip.getStartDate(),
                trip.getStatus().value(), plan.size(), List.copyOf(resultDays));
    }

    /** Derives a bounded search circle from Google coordinates, never client-supplied POI positions. */
    private static GenerateTripRequest.SearchArea areaAround(List<List<PoiVO>> days) {
        List<PoiVO> points = days.stream().flatMap(List::stream).toList();
        double x = 0, y = 0, z = 0;
        for (PoiVO poi : points) {
            double latitude = Math.toRadians(poi.latitude());
            double longitude = Math.toRadians(poi.longitude());
            x += Math.cos(latitude) * Math.cos(longitude);
            y += Math.cos(latitude) * Math.sin(longitude);
            z += Math.sin(latitude);
        }
        double horizontal = Math.hypot(x, y);
        if (Math.hypot(horizontal, z) < 1e-9)
            throw ApiError.invalid("mustVisitPois", "Required POIs are too far apart");
        double latitude = Math.toDegrees(Math.atan2(z, horizontal));
        double longitude = Math.toDegrees(Math.atan2(y, x));
        double furthestMeters = points.stream().mapToDouble(
                poi -> haversineKm(latitude, longitude, poi.latitude(), poi.longitude()) * 1000)
                .max().orElse(0);
        double needed = Math.max(DEFAULT_RADIUS_METERS, furthestMeters + SEARCH_MARGIN_METERS);
        if (needed > MAX_RADIUS_METERS)
            throw ApiError.invalid("mustVisitPois", "Required POIs exceed the supported travel area");
        return new GenerateTripRequest.SearchArea(latitude, longitude, (int) Math.ceil(needed));
    }

    private static boolean usable(PoiVO poi) {
        return poi != null && poi.googlePlaceId() != null && !poi.googlePlaceId().isBlank()
                && poi.googlePlaceId().length() <= 225
                && poi.latitude() != null && Double.isFinite(poi.latitude())
                && Math.abs(poi.latitude()) <= 90
                && poi.longitude() != null && Double.isFinite(poi.longitude())
                && Math.abs(poi.longitude()) <= 180
                && !"CLOSED_PERMANENTLY".equals(poi.businessStatus())
                && !"CLOSED_TEMPORARILY".equals(poi.businessStatus());
    }

    /** Seeds empty days, then chooses the best legal insertion across the entire trip. */
    static List<List<PoiVO>> plan(List<List<PoiVO>> requiredDays, List<PoiVO> candidates,
            int maxPoisPerDay, List<String> preferredTypes) {
        List<List<PoiVO>> days = new ArrayList<>();
        Set<String> selected = new HashSet<>();
        for (List<PoiVO> required : requiredDays) {
            days.add(new ArrayList<>(required));
            for (PoiVO poi : required) selected.add(poi.googlePlaceId());
        }
        List<PoiVO> remaining = new ArrayList<>();
        for (PoiVO poi : candidates) {
            if (selected.add(poi.googlePlaceId())) remaining.add(poi);
        }

        for (int day = 0; day < days.size(); day++) {
            if (!days.get(day).isEmpty()) continue;
            Choice seed = bestForDay(days, remaining, day, preferredTypes);
            if (seed == null) throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INSUFFICIENT_POIS", "Not enough places to give every day a destination");
            days.get(day).add(seed.position(), seed.poi());
            remaining.remove(seed.poi());
        }

        while (!remaining.isEmpty()) {
            Choice best = null;
            for (PoiVO poi : remaining) {
                for (int day = 0; day < days.size(); day++) {
                    if (days.get(day).size() >= maxPoisPerDay) continue;
                    for (int position = 0; position <= days.get(day).size(); position++) {
                        Choice choice = score(days, poi, day, position, preferredTypes);
                        if (best == null || choice.score() > best.score()) best = choice;
                    }
                }
            }
            if (best == null || best.score() < MIN_OPTIONAL_SCORE) break;
            days.get(best.day()).add(best.position(), best.poi());
            remaining.remove(best.poi());
        }
        return days.stream().map(List::copyOf).toList();
    }

    private static Choice bestForDay(List<List<PoiVO>> days, List<PoiVO> remaining, int day,
            List<String> preferredTypes) {
        Choice best = null;
        for (PoiVO poi : remaining) {
            Choice choice = score(days, poi, day, 0, preferredTypes);
            if (best == null || choice.score() > best.score()) best = choice;
        }
        return best;
    }

    private static Choice score(List<List<PoiVO>> days, PoiVO poi, int day, int position,
            List<String> preferredTypes) {
        double before = totalDistanceKm(days);
        days.get(day).add(position, poi);
        double after = totalDistanceKm(days);
        days.get(day).remove(position);
        double delta = Math.max(0, after - before);
        return new Choice(poi, day, position,
                quality(poi, preferredTypes) - DISTANCE_PENALTY_PER_KM * delta);
    }

    /** Includes the edge from the last POI of one day to the first POI of the next. */
    static double totalDistanceKm(List<List<PoiVO>> days) {
        PoiVO previous = null;
        double total = 0;
        for (List<PoiVO> day : days) {
            for (PoiVO current : day) {
                if (previous != null) total += haversineKm(previous.latitude(), previous.longitude(),
                        current.latitude(), current.longitude());
                previous = current;
            }
        }
        return total;
    }

    private static double haversineKm(double lat1, double lon1, double lat2, double lon2) {
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.pow(Math.sin(dLat / 2), 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.pow(Math.sin(dLon / 2), 2);
        return 2 * EARTH_RADIUS_KM * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
    }

    private static double quality(PoiVO poi, List<String> preferredTypes) {
        boolean matches = preferredTypes.isEmpty()
                || preferredTypes.contains(poi.primaryType())
                || poi.types() != null && poi.types().stream().anyMatch(preferredTypes::contains);
        double preference = matches ? 1.0 : 0.5;
        double rating = poi.rating() == null ? 0.5 : clamp(poi.rating() / 5.0);
        double count = poi.userRatingCount() == null ? 0
                : clamp(Math.log1p(poi.userRatingCount()) / Math.log1p(5000));
        return 0.4 * preference + 0.6 * (0.75 * rating + 0.25 * count);
    }

    private static double clamp(double value) {
        return Math.max(0, Math.min(1, value));
    }

    private record Choice(PoiVO poi, int day, int position, double score) {}

    @Override
    @Transactional(readOnly = true)
    public List<TripSummary> list(TokenService.Identity identity) {
        if (identity == null)
            throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        return tripRepository.findByUserIdOrderByIdDesc(identity.userId()).stream()
                .map(t -> new TripSummary(t.getId(), t.getName(), t.getStatus().value(), t.getDayCount()))
                .toList();
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

        TripStatus current = trip.getStatus();
        if (!current.canTransitionTo(next)) {
            throw new ApiError(HttpStatus.CONFLICT, "INVALID_TRIP_STATUS",
                    "Cannot change trip from " + current.value() + " to " + next.value());
        }
        trip.setStatus(next);
    }
}
