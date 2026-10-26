package com.travel.repositories;

import com.travel.entity.TripPoi;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripPoiRepository extends JpaRepository<TripPoi, Long> {
}
