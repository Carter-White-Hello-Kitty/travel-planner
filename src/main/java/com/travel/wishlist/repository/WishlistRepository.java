package com.travel.wishlist.repository;

import com.travel.wishlist.entity.Wishlist;
import java.util.Optional;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    @org.springframework.data.jpa.repository.Query("select w.googlePlaceId from Wishlist w where w.user.id = :userId")
    java.util.Set<String> findPlaceIdsByUserId(@org.springframework.data.repository.query.Param("userId") Long userId);

    Page<Wishlist> findByUserId(Long userId, Pageable pageable);

    boolean existsByUserIdAndGooglePlaceId(Long userId, String googlePlaceId);

    Optional<Wishlist> findByUserIdAndGooglePlaceId(Long userId, String googlePlaceId);
}