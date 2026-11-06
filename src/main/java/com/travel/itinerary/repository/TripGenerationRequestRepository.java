package com.travel.itinerary.repository;

import com.travel.itinerary.entity.TripGenerationRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TripGenerationRequestRepository extends JpaRepository<TripGenerationRequest, Long> {
    Optional<TripGenerationRequest> findByUserIdAndRequestId(Long userId, String requestId);
}
