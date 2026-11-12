package com.travel.itinerary.vo;

/** Minimal response for creating or auto-saving a draft. */
public record SavedTripResult(Long tripId, Long revision) {}
