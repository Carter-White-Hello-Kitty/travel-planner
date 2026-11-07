package com.travel.itinerary.repository;

import com.travel.itinerary.entity.TripPoi;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripPoiRepository extends JpaRepository<TripPoi, Long> {
    List<TripPoi> findByTripDayIdOrderBySequenceAsc(Long tripDayId);
}
