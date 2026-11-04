package com.travel.itinerary.repository;

import com.travel.itinerary.entity.Trip;
import com.travel.itinerary.vo.TripSummary;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.List;

public interface TripRepository extends JpaRepository<Trip, Long> {

    @Query("select new com.travel.itinerary.vo.TripSummary(t.id, t.name, t.status, t.dayCount) "
            + "from Trip t where t.user.id = :userId order by t.id desc")
    List<TripSummary> findSummariesByUserId(@Param("userId") Long userId);

    /** Loads a trip with a row lock so concurrent status changes run one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from Trip t where t.id = :id")
    Optional<Trip> findByIdForUpdate(@Param("id") Long id);
}
