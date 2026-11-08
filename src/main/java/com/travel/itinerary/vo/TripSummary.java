package com.travel.itinerary.vo;

/** The fields needed to display one trip in the current user's list. */
public record TripSummary(Long tripId, String name, String status, Integer dayCount) {
}
