package com.travel.auth.repository;

import com.travel.auth.entity.AuthMailJob;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface AuthMailJobRepository extends JpaRepository<AuthMailJob, String> {

    @Query("select j from AuthMailJob j where j.status in :statuses and j.nextAttemptAt <= :now order by j.createdAt asc, j.id asc")
    List<AuthMailJob> findEligible(@Param("statuses") List<String> statuses, @Param("now") LocalDateTime now);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select j from AuthMailJob j where j.id = :id")
    Optional<AuthMailJob> findByIdForUpdate(@Param("id") String id);

    @Modifying
    @Query("delete from AuthMailJob j where j.status in :statuses and j.createdAt < :cutoff")
    int deleteByStatusInAndCreatedAtBefore(@Param("statuses") List<String> statuses, @Param("cutoff") LocalDateTime cutoff);
}
