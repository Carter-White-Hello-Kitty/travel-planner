package com.travel.itinerary.service.impl;

import com.travel.common.ApiError;
import com.travel.itinerary.service.TripGenerationService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.service.PoiSearchService;
import com.travel.poisearch.vo.PoiSearchRequest;
import com.travel.poisearch.vo.PoiVO;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

@Service
public class TripGenerationServiceImpl implements TripGenerationService {
    private static final int DEFAULT_RADIUS_METERS = 20_000;
    private static final int SEARCH_MARGIN_METERS = 5_000;
    private static final int MAX_RADIUS_METERS = 50_000;
    private static final double DISTANCE_PENALTY_PER_KM = 0.05;
    private static final double MIN_OPTIONAL_SCORE = 0.60;
    private static final double EARTH_RADIUS_KM = 6371.0088;

    private final GooglePlacesClient google;
    private final PoiSearchService search;

    public TripGenerationServiceImpl(GooglePlacesClient google, PoiSearchService search) {
        this.google = google;
        this.search = search;
    }

    @Override
    public List<List<PoiVO>> generatePlan(GenerateTripRequest request, Duration maxDuration) {
        long deadline = System.nanoTime() + maxDuration.toNanos();

        List<List<PoiVO>> required = new ArrayList<>();
        for (int day = 0; day < request.dayCount(); day++) required.add(new ArrayList<>());
        Set<String> requiredIds = new HashSet<>();
        if (request.tripDays() != null) {
            for (GenerateTripRequest.Day day : request.tripDays()) {
                for (GenerateTripRequest.MustVisitPoi item : day.mustVisitPois()) {
                    ensureWithinDeadline(deadline);
                    PoiVO poi = google.details(item.googlePlaceId());
                    ensureWithinDeadline(deadline);
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
            ensureWithinDeadline(deadline);
            for (PoiVO poi : search.search(new PoiSearchRequest(
                    query, area.latitude(), area.longitude(), area.radiusMeters()))) {
                if (usable(poi) && !requiredIds.contains(poi.googlePlaceId()))
                    candidates.putIfAbsent(poi.googlePlaceId(), poi);
            }
            ensureWithinDeadline(deadline);
        }

        List<List<PoiVO>> plan = plan(required, List.copyOf(candidates.values()),
                request.constraints().maxPoisPerDay(), preferredTypes, deadline);
        ensureWithinDeadline(deadline);
        return plan;
    }

    private static void ensureWithinDeadline(long deadline) {
        if (deadline == Long.MAX_VALUE) return;
        if (System.nanoTime() - deadline >= 0)
            throw new ApiError(HttpStatus.GATEWAY_TIMEOUT, "GENERATION_TIMEOUT",
                    "Trip generation timed out");
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
        return plan(requiredDays, candidates, maxPoisPerDay, preferredTypes, Long.MAX_VALUE);
    }

    private static List<List<PoiVO>> plan(List<List<PoiVO>> requiredDays, List<PoiVO> candidates,
            int maxPoisPerDay, List<String> preferredTypes, long deadline) {
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
            Choice seed = bestForDay(days, remaining, day, preferredTypes, deadline);
            if (seed == null) throw new ApiError(HttpStatus.UNPROCESSABLE_ENTITY,
                    "INSUFFICIENT_POIS", "Not enough places to give every day a destination");
            days.get(day).add(seed.position(), seed.poi());
            remaining.remove(seed.poi());
        }

        while (!remaining.isEmpty()) {
            ensureWithinDeadline(deadline);
            Choice best = null;
            for (PoiVO poi : remaining) {
                ensureWithinDeadline(deadline);
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
            List<String> preferredTypes, long deadline) {
        Choice best = null;
        for (PoiVO poi : remaining) {
            ensureWithinDeadline(deadline);
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
}
