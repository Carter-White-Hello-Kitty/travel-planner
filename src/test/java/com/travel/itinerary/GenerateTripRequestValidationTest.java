package com.travel.itinerary;

import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import jakarta.validation.Validation;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import static org.junit.jupiter.api.Assertions.*;

class GenerateTripRequestValidationTest {
    GenerateTripRequest request(int days) {
        return new GenerateTripRequest("Boundary", LocalDate.of(2026, 12, 1), days,
                new GenerateTripRequest.SearchArea(40.0, -75.0, 5000),
                new GenerateTripRequest.Constraints(3, List.of()), null);
    }
    @Test void acceptsOneAndFifteenDaysAndRejectsZeroAndSixteen() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();
            assertTrue(validator.validate(request(1)).isEmpty());
            assertTrue(validator.validate(request(15)).isEmpty());
            assertFalse(validator.validate(request(0)).isEmpty());
            assertFalse(validator.validate(request(16)).isEmpty());
        }
    }
    @Test void oldCachedResponseWithoutDestinationStillDeserializes() {
        var trip = JsonMapper.builder().build().readValue(
                "{\"tripId\":1,\"name\":\"Existing\",\"startDate\":null,\"status\":\"Planning\",\"dayCount\":1,\"tripDays\":[]}",
                GeneratedTrip.class);
        assertEquals(1L, trip.tripId());
        assertNull(trip.destination());
    }
}
