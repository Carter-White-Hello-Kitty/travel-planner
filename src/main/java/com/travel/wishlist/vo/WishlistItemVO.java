package com.travel.wishlist.vo;

import java.time.Instant;

/** One wishlist entry: persisted POI identity plus the moment it was saved. */
public record WishlistItemVO(
        Long poiId,
        String googlePlaceId,
        Instant createdAt) {
}