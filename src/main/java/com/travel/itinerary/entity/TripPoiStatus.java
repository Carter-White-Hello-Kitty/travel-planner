package com.travel.itinerary.entity;

/**
 * Progress rules for {@link TripPoi#getStatus()}; the converter stores {@link #value()} in SQL.
 *
 * <pre>
 * Planned ──location detected──▶ Passed ──check-in──▶ Visited
 * Planned ─────────────────check-in─────────────────▶ Visited
 * </pre>
 */
public enum TripPoiStatus {
    PLANNED("Planned"),
    PASSED("Passed"),
    VISITED("Visited");

    private final String value;

    TripPoiStatus(String value) {
        this.value = value;
    }

    /** The string stored in the database and returned in JSON. */
    public String value() {
        return value;
    }

    /** Parses a stored status string, e.g. {@code "Planned"}. */
    public static TripPoiStatus from(String value) {
        for (TripPoiStatus status : values()) {
            if (status.value.equals(value)) return status;
        }
        throw new IllegalArgumentException("Unknown trip POI status: " + value);
    }

    /** Whether a POI in this status may move to {@code next}. */
    public boolean canTransitionTo(TripPoiStatus next) {
        return switch (this) {
            case PLANNED -> next == PASSED || next == VISITED;
            case PASSED -> next == VISITED;
            case VISITED -> false;
        };
    }

    /** Visited POIs are a record of the trip and may not be removed or reordered. */
    public boolean isEditable() {
        return this != VISITED;
    }
}
