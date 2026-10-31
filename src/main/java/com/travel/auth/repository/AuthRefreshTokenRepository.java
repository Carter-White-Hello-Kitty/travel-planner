package com.travel.auth.repository;

import com.travel.auth.entity.AuthRefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Optional;

public interface AuthRefreshTokenRepository extends JpaRepository<AuthRefreshToken, String> {

    Optional<AuthRefreshToken> findByTokenHash(String tokenHash);

    @Modifying
    @Query("delete from AuthRefreshToken t where t.sessionId in (select s.id from AuthSession s where s.expiresAt < :cutoff)")
    void deleteBySessionExpiredBefore(@Param("cutoff") LocalDateTime cutoff);
}
