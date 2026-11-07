package com.travel.itinerary.repository;

import com.travel.itinerary.entity.TripPoi;
import com.travel.itinerary.entity.TripPoiStatus;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripPoiRepository extends JpaRepository<TripPoi, Long> {
    List<TripPoi> findByTripDayIdOrderBySequenceAsc(Long tripDayId);

    @Query("select poi.tripDay.trip.id from TripPoi poi where poi.id = :tripPoiId")
    Optional<Long> findTripIdByTripPoiId(@Param("tripPoiId") Long tripPoiId);

    // Exclude the POI being checked in so the count does not depend on an early JPA flush.
    @Query("select count(poi) from TripPoi poi where poi.tripDay.trip.id = :tripId "
            + "and poi.id <> :checkedInPoiId and poi.status <> :visited")
    long countOtherUnvisited(@Param("tripId") Long tripId,
            @Param("checkedInPoiId") Long checkedInPoiId,
            @Param("visited") TripPoiStatus visited);
}
