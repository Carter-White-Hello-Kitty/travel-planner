package com.travel.itinerary.repository;

import com.travel.itinerary.entity.TripDay;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripDayRepository extends JpaRepository<TripDay, Long> {
    List<TripDay> findByTripIdOrderByDayNumberAsc(Long tripId);

    @Modifying
    @Query("delete from TripDay day where day.trip.id = :tripId")
    int deleteByTripId(@Param("tripId") Long tripId);
}
