package com.travel.wishlist.repository;

import com.travel.wishlist.entity.Wishlist;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface WishlistRepository extends JpaRepository<Wishlist, Long> {

    @Query("select w.googlePlaceId from Wishlist w where w.user.id = :userId")
    Set<String> findPlaceIdsByUserId(@Param("userId") Long userId);

    Page<Wishlist> findByUserId(Long userId, Pageable pageable);

    boolean existsByUserIdAndGooglePlaceId(Long userId, String googlePlaceId);

    Optional<Wishlist> findByUserIdAndGooglePlaceId(Long userId, String googlePlaceId);
}
