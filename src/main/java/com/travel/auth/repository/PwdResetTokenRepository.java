package com.travel.auth.repository;

import com.travel.auth.entity.PwdResetToken;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

public interface PwdResetTokenRepository extends JpaRepository<PwdResetToken, Long> {

    Optional<PwdResetToken> findByTokenHash(String tokenHash);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PwdResetToken t where t.tokenHash = :hash and t.expiresAt > :now")
    List<PwdResetToken> findValidByTokenHashForUpdate(@Param("hash") String hash, @Param("now") LocalDateTime now);

    @Modifying
    @Query("delete from PwdResetToken t where t.user.id = :userId")
    int deleteByUserId(@Param("userId") Long userId);

    @Modifying
    @Query("delete from PwdResetToken t where t.tokenHash = :hash")
    int deleteByTokenHash(@Param("hash") String hash);

    @Modifying
    @Query("delete from PwdResetToken t where t.expiresAt < :cutoff")
    int deleteByExpiresAtBefore(@Param("cutoff") LocalDateTime cutoff);
}
