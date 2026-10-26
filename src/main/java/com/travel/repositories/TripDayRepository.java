package com.travel.repositories;

import com.travel.entity.TripDay;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripDayRepository extends JpaRepository<TripDay, Long> {
}
