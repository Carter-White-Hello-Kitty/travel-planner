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
@Table(name = "auth_mail_jobs")
public class AuthMailJob {

    @Id
    @Column(length = 36, columnDefinition = "CHAR(36)")
    private String id;

    @Column(nullable = false, length = 320)
    private String email;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "token_hash", length = 64, columnDefinition = "CHAR(64)")
    private String tokenHash;

    @Column(name = "encrypted_payload")
    private byte[] encryptedPayload;

    @Column(nullable = false)
    private Integer attempts;

    @Column(name = "next_attempt_at", nullable = false)
    private LocalDateTime nextAttemptAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "created_at", insertable = false, updatable = false)
    private LocalDateTime createdAt;
}
