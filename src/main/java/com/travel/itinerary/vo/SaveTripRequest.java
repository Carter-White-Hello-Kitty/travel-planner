package com.travel.itinerary.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** The entire editable plan; array order is day and visit order. */
public record SaveTripRequest(
        @NotBlank @Size(max = 255) String name,
        LocalDate startDate,
        @Min(0) Long revision,
        @NotNull @Size(max = 14) List<@NotNull @Valid Day> tripDays) {

    public record Day(@NotNull @Size(max = 10) List<@NotBlank @Size(max = 225) String> tripPois) {}

    @AssertTrue(message = "A POI can appear only once in a trip")
    public boolean isUniquePois() {
        if (tripDays == null) return true;
        Set<String> ids = new HashSet<>();
        for (Day day : tripDays) {
            if (day == null || day.tripPois() == null) continue;
            for (String id : day.tripPois()) {
                if (id != null && !ids.add(id)) return false;
            }
        }
        return true;
    }
}
