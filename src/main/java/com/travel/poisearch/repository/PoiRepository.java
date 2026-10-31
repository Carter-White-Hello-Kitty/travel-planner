package com.travel.poisearch.repository;

import com.travel.poisearch.entity.Poi;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PoiRepository extends JpaRepository<Poi, Long> {
}
