package com.travel.itinerary.vo;

/** Statuses after a successful POI check-in. */
public record TripCheckInResult(Long tripPoiId, String poiStatus, String tripStatus) {}
