package com.travel.itinerary.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.LocalDateTime;
import lombok.Getter;
import lombok.Setter;

/** A committed generation operation. The temporary PROCESSING state lives only in Redis. */
@Getter
@Setter
@Entity
@Table(name = "trip_generation_requests", uniqueConstraints =
        @UniqueConstraint(name = "uq_trip_generation_user_request", columnNames = {"user_id", "request_id"}))
public class TripGenerationRequest {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "trip_id", nullable = false)
    private Long tripId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
