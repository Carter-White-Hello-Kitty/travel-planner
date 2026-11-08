package com.travel.wishlist.vo;

import java.time.Instant;

/**
 * One wishlist entry: stable Google Place ID, live display fields loaded from Places,
 * and the moment it was saved.
 */
public record WishlistItemVO(
        String googlePlaceId,
        String name,
        String address,
        Double latitude,
        Double longitude,
        String category,
        Double rating,
        Instant createdAt) {
}