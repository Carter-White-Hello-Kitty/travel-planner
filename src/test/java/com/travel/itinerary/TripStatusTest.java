package com.travel.itinerary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.travel.itinerary.entity.TripPoi;
import com.travel.itinerary.entity.TripPoiStatus;
import com.travel.itinerary.entity.TripStatus;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Checks every (from, to) pair of both state machines against the allowed edges. */
class TripStatusTest {

    private static final Set<String> TRIP_EDGES = Set.of(
            "Planning->Ongoing",
            "Ongoing->Completed",
            "Ongoing->Cancelled");

    private static final Set<String> TRIP_POI_EDGES = Set.of(
            "Planned->Passed",
            "Planned->Visited",
            "Passed->Visited");

    @Test
    void tripTransitionsMatchStateMachine() {
        for (TripStatus from : TripStatus.values()) {
            for (TripStatus to : TripStatus.values()) {
                String edge = from.value() + "->" + to.value();
                assertEquals(TRIP_EDGES.contains(edge), from.canTransitionTo(to), edge);
            }
        }
    }

    @Test
    void tripPoiTransitionsMatchStateMachine() {
        for (TripPoiStatus from : TripPoiStatus.values()) {
            for (TripPoiStatus to : TripPoiStatus.values()) {
                String edge = from.value() + "->" + to.value();
                assertEquals(TRIP_POI_EDGES.contains(edge), from.canTransitionTo(to), edge);
            }
        }
    }

    @Test
    void storedValuesMatchDesignDocAndRoundTrip() {
        assertEquals(List.of("Planning", "Ongoing", "Completed", "Cancelled"),
                List.of(TripStatus.values()).stream().map(TripStatus::value).toList());
        assertEquals(List.of("Planned", "Passed", "Visited"),
                List.of(TripPoiStatus.values()).stream().map(TripPoiStatus::value).toList());
        for (TripStatus s : TripStatus.values()) assertEquals(s, TripStatus.from(s.value()));
        for (TripPoiStatus s : TripPoiStatus.values()) assertEquals(s, TripPoiStatus.from(s.value()));
        assertThrows(IllegalArgumentException.class, () -> TripStatus.from("planning"));
        assertThrows(IllegalArgumentException.class, () -> TripPoiStatus.from(null));
    }

    @Test
    void tripPoiDefaultIsPlanned() {
        assertEquals(TripPoiStatus.PLANNED, TripPoiStatus.from(new TripPoi().getStatus()));
    }

    @Test
    void onlyPlanningAndOngoingTripsAreEditable() {
        for (TripStatus s : TripStatus.values()) {
            boolean editable = s == TripStatus.PLANNING || s == TripStatus.ONGOING;
            assertEquals(editable, s.isEditable(), s.value());
        }
    }

    @Test
    void visitedPoisAreLocked() {
        for (TripPoiStatus s : TripPoiStatus.values()) {
            assertEquals(s != TripPoiStatus.VISITED, s.isEditable(), s.value());
        }
    }

    @Test
    void onlyFinishedTripsArePublishable() {
        for (TripStatus s : TripStatus.values()) {
            boolean finished = s == TripStatus.COMPLETED || s == TripStatus.CANCELLED;
            assertEquals(finished, s.isPublishable(), s.value());
        }
    }
}
