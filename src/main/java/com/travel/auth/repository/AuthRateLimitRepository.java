package com.travel.auth.repository;

import com.travel.auth.entity.AuthRateLimit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;

public interface AuthRateLimitRepository extends JpaRepository<AuthRateLimit, String> {

    @Modifying
    @Query(
            value = "INSERT INTO auth_rate_limits(bucket_key, count, expires_at) VALUES (:bucket, 1, :expiry) "
                    + "ON DUPLICATE KEY UPDATE count = count + 1",
            nativeQuery = true)
    void increment(@Param("bucket") String bucket, @Param("expiry") LocalDateTime expiry);

    @Modifying
    @Query("delete from AuthRateLimit r where r.expiresAt < :cutoff")
    void deleteByExpiresAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
