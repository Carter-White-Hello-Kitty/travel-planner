package com.travel.wishlist.vo;

import java.time.Instant;

public record WishlistAddedVO(String googlePlaceId, Instant createdAt) {
}