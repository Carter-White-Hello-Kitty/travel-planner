package com.travel.repositories;

import com.travel.entity.PwdResetToken;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PwdResetTokenRepository extends JpaRepository<PwdResetToken, Long> {
}
