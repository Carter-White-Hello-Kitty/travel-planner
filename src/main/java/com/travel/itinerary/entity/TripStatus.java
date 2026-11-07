package com.travel.itinerary.entity;

/**
 * Lifecycle rules for {@link Trip#getStatus()}; the converter stores {@link #value()} in SQL.
 *
 * <pre>
 * Planning ──start──▶ Ongoing ──all POIs visited──▶ Completed
 *                        └──────────cancel─────────▶ Cancelled
 * </pre>
 */
public enum TripStatus {
    PLANNING("Planning"),
    ONGOING("Ongoing"),
    COMPLETED("Completed"),
    CANCELLED("Cancelled");

    private final String value;

    TripStatus(String value) {
        this.value = value;
    }

    /** The string stored in the database and returned in JSON. */
    public String value() {
        return value;
    }

    /** Parses a stored status string, e.g. {@code "Planning"}. */
    public static TripStatus from(String value) {
        for (TripStatus status : values()) {
            if (status.value.equals(value)) return status;
        }
        throw new IllegalArgumentException("Unknown trip status: " + value);
    }

    /** Whether a trip in this status may move to {@code next}. */
    public boolean canTransitionTo(TripStatus next) {
        return switch (this) {
            case PLANNING -> next == ONGOING;
            case ONGOING -> next == COMPLETED || next == CANCELLED;
            case COMPLETED, CANCELLED -> false;
        };
    }

    /**
     * Whether the trip's days and POIs may be edited. Ongoing trips stay editable so travelers can
     * adjust plans mid-trip, but only POIs that are still {@link TripPoiStatus#isEditable()}.
     */
    public boolean isEditable() {
        return this == PLANNING || this == ONGOING;
    }

    /** A travel post may only be published for a finished trip. */
    public boolean isPublishable() {
        return this == COMPLETED || this == CANCELLED;
    }
}
