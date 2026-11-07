package com.travel.itinerary.vo;

import java.time.LocalDate;
import java.util.List;

/** Service result; arrays are already in date and visit order. */
public record GeneratedTrip(
        Long tripId,
        String name,
        LocalDate startDate,
        String status,
        Integer dayCount,
        List<Day> tripDays) {

    public record Day(Integer dayNumber, List<Poi> tripPois) {}

    public record Poi(
            Long tripPoiId,
            String googlePlaceId,
            String name,
            String address,
            Double latitude,
            Double longitude,
            String status) {}
}
