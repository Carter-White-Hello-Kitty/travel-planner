package com.travel.itinerary.vo;

import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;

/** Explicit day and visit order for manual planning; no synthetic route distances. */
public record SaveTripRequest(
        @NotBlank @Size(max = 255) String name,
        @NotBlank @Size(max = 255) String destination,
        @NotNull LocalDate startDate,
        @NotNull @Size(min = 1, max = 15)
        List<@NotNull @Size(max = 10) List<@NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,225}") String>> days) {
    @AssertTrue(message = "A place can appear only once in a trip")
    public boolean isUnique() {
        if (days == null) return true;
        var seen = new HashSet<String>();
        for (var day : days) if (day != null)
            for (String id : day) if (!seen.add(id)) return false;
        return true;
    }
}
