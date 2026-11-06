package com.travel.poisearch.repository;

import com.travel.poisearch.entity.Poi;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface PoiRepository extends JpaRepository<Poi, Long> {
    Optional<Poi> findByGooglePlaceId(String googlePlaceId);
}
