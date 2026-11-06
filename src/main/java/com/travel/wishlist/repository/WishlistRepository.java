package com.travel.wishlist.repository;

import com.travel.wishlist.entity.Wishlist;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {
    Page<Wishlist> findByUserId(Long userId, Pageable pageable);
    boolean existsByUserIdAndPoiId(Long userId, Long poiId);
    Optional<Wishlist> findByUserIdAndPoiId(Long userId, Long poiId);
}
