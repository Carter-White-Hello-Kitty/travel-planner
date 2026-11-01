package com.travel.auth.repository;

import com.travel.auth.entity.AuthWorkerLock;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public interface AuthWorkerLockRepository extends JpaRepository<AuthWorkerLock, Integer> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select l from AuthWorkerLock l where l.id = 1")
    void lock();
}
