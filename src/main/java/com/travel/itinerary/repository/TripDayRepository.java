package com.travel.itinerary.repository;

import com.travel.itinerary.entity.TripDay;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripDayRepository extends JpaRepository<TripDay, Long> {
}
