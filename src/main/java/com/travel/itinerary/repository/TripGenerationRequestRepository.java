package com.travel.itinerary.repository;

import com.travel.itinerary.entity.TripGenerationRequest;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TripGenerationRequestRepository extends JpaRepository<TripGenerationRequest, Long> {
    Optional<TripGenerationRequest> findByUserIdAndRequestId(Long userId, String requestId);

    Optional<TripGenerationRequest> findByTripId(Long tripId);

    @Modifying
    @Query("delete from TripGenerationRequest request where request.tripId = :tripId")
    void deleteByTripId(@Param("tripId") Long tripId);
}
