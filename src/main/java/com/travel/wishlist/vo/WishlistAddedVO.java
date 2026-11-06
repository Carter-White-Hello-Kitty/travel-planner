package com.travel.wishlist.vo;

import java.time.Instant;

public record WishlistAddedVO(Long poiId, Instant createdAt) {
}