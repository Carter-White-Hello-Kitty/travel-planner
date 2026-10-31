package com.travel.auth.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(name = "auth_rate_limits")
public class AuthRateLimit {

    @Id
    @Column(name = "bucket_key", nullable = false, length = 128)
    private String bucketKey;

    @Column(nullable = false)
    private Integer count;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;
}
