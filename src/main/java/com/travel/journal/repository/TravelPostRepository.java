package com.travel.journal.repository;

import com.travel.journal.entity.TravelPost;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TravelPostRepository extends JpaRepository<TravelPost, Long> {
}
