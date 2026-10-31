package com.travel.itinerary.repository;

import com.travel.itinerary.entity.TripPoi;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripPoiRepository extends JpaRepository<TripPoi, Long> {
}
