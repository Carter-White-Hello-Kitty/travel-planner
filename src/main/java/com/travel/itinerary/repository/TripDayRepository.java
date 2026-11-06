package com.travel.itinerary.repository;

import com.travel.itinerary.entity.TripDay;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripDayRepository extends JpaRepository<TripDay, Long> {
    List<TripDay> findByTripIdOrderByDayNumberAsc(Long tripId);
}
