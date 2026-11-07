package com.travel.wishlist.entity;

import com.travel.auth.entity.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@Entity
@Table(
        name = "wishlist",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_wishlist_user_google_place",
                columnNames = {"user_id", "google_place_id"}
        )
)
public class Wishlist {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(name = "google_place_id", nullable = false, length = 225)
    private String googlePlaceId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
}
