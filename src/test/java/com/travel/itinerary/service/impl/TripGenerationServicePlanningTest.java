package com.travel.itinerary.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.travel.common.ApiError;
import com.travel.poisearch.vo.PoiVO;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class TripGenerationServicePlanningTest {

    @Test
    void seedsEmptyDayThenInsertsAcrossTheDayBoundaryWithoutReorderingRequiredPois() {
        PoiVO a = poi("A", 0, 0, 4.5, 1000);
        PoiVO b = poi("B", 0, 0.01, 4.5, 1000);
        PoiVO c = poi("C", 0, 0.03, 4.5, 1000);
        PoiVO x = poi("X", 0, 0.02, 4.3, 600);
        PoiVO nearDay3 = poi("D", 0, 0.04, 4.5, 1000);
        PoiVO farDay3 = poi("F", 0, 1, 5, 5000);

        List<List<PoiVO>> plan = TripGenerationServiceImpl.plan(
                List.of(List.of(a, b), List.of(c), List.of()),
                List.of(farDay3, nearDay3, x), 3, List.of());

        assertEquals(List.of("A", "B", "X"), ids(plan.get(0)));
        assertEquals(List.of("C"), ids(plan.get(1)));
        assertEquals(List.of("D"), ids(plan.get(2)));
        assertEquals(TripGenerationServiceImpl.totalDistanceKm(List.of(List.of(a, b), List.of(c))),
                TripGenerationServiceImpl.totalDistanceKm(List.of(List.of(a, b, c))), 0.000001);
    }

    @Test
    void rejectsAnUnseedableDayRatherThanSavingAnIncompleteTrip() {
        ApiError error = assertThrows(ApiError.class, () ->
                TripGenerationServiceImpl.plan(List.of(List.of(), List.of()), List.of(), 2, List.of()));
        assertEquals(HttpStatus.UNPROCESSABLE_ENTITY, error.status);
        assertEquals("INSUFFICIENT_POIS", error.code);
    }

    @Test
    void doesNotFillADayWithAnUnacceptablyDistantOptionalPoi() {
        PoiVO a = poi("A", 0, 0, 4.5, 1000);
        PoiVO far = poi("F", 0, 1, 5, 5000);
        List<List<PoiVO>> plan = TripGenerationServiceImpl.plan(List.of(List.of(a)), List.of(far), 3, List.of());
        assertEquals(List.of("A"), ids(plan.get(0)));
    }

    private static List<String> ids(List<PoiVO> pois) {
        return pois.stream().map(PoiVO::googlePlaceId).toList();
    }

    private static PoiVO poi(String id, double latitude, double longitude, double rating, long count) {
        return new PoiVO(id, id, "Example", latitude, longitude,
                "museum", rating, "museum", List.of("museum"), count, "OPERATIONAL");
    }
}
