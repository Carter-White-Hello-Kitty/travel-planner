package com.travel.repositories;

import com.travel.entity.TravelPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPostRepository extends JpaRepository<TravelPost, Long> {
}
