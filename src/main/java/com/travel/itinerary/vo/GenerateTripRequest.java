package com.travel.itinerary.vo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Service input. The order of each mustVisitPois list is the required relative visit order. */
public record GenerateTripRequest(
        @NotBlank @Size(max = 255) String name,
        @NotNull LocalDate startDate,
        @NotNull @Min(1) @Max(14) Integer dayCount,
        @Valid SearchArea searchArea,
        @NotNull @Valid Constraints constraints,
        List<@NotNull @Valid Day> tripDays,
        @NotNull @Min(0) Long revision) {

    public GenerateTripRequest(String name, LocalDate startDate, Integer dayCount,
            SearchArea searchArea, Constraints constraints, List<Day> tripDays) {
        this(name, startDate, dayCount, searchArea, constraints, tripDays, null);
    }

    public record SearchArea(
            @NotNull @DecimalMin("-90.0") @DecimalMax("90.0") Double latitude,
            @NotNull @DecimalMin("-180.0") @DecimalMax("180.0") Double longitude,
            @NotNull @Min(1) @Max(50000) Integer radiusMeters) {

        @AssertTrue(message = "Search coordinates must be finite")
        public boolean isFinite() {
            return latitude == null || longitude == null
                    || Double.isFinite(latitude) && Double.isFinite(longitude);
        }
    }

    public record Constraints(
            @NotNull @Min(1) @Max(10) Integer maxPoisPerDay,
            @Size(max = 5) List<@NotBlank @Pattern(regexp = "[a-z0-9_]{1,100}") String> preferredTypes) {}

    public record Day(
            @NotNull @Min(1) Integer dayNumber,
            @NotNull List<@NotNull @Valid MustVisitPoi> mustVisitPois) {}

    public record MustVisitPoi(@NotBlank @Size(max = 225) String googlePlaceId) {}

    @AssertTrue(message = "Choose either a city area or at least one required POI")
    public boolean isPlanningModeValid() {
        if (searchArea != null) {
            return tripDays == null || tripDays.stream().allMatch(
                    day -> day == null || day.mustVisitPois() == null || day.mustVisitPois().isEmpty());
        }
        return tripDays != null && tripDays.stream().filter(day -> day != null && day.mustVisitPois() != null)
                .anyMatch(day -> !day.mustVisitPois().isEmpty());
    }

    @AssertTrue(message = "Trip days must cover day numbers 1 through dayCount exactly once")
    public boolean isTripDaysValid() {
        if (dayCount == null || tripDays == null || tripDays.isEmpty())
            return searchArea != null;
        if (tripDays.size() != dayCount) return false;
        Set<Integer> numbers = new HashSet<>();
        for (Day day : tripDays) {
            if (day == null || day.dayNumber() == null || day.dayNumber() < 1
                    || day.dayNumber() > dayCount || !numbers.add(day.dayNumber())) return false;
        }
        return true;
    }

    @AssertTrue(message = "Required POIs must be unique and fit within each day's capacity")
    public boolean isMustVisitPoisValid() {
        if (tripDays == null || constraints == null || constraints.maxPoisPerDay() == null) return true;
        Set<String> ids = new HashSet<>();
        for (Day day : tripDays) {
            if (day == null || day.mustVisitPois() == null) continue;
            if (day.mustVisitPois().size() > constraints.maxPoisPerDay()) return false;
            for (MustVisitPoi poi : day.mustVisitPois()) {
                if (poi != null && poi.googlePlaceId() != null && !ids.add(poi.googlePlaceId())) return false;
            }
        }
        return true;
    }
}
