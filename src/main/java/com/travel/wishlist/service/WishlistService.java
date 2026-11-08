package com.travel.wishlist.service;

import com.travel.wishlist.vo.WishlistAddedVO;
import com.travel.wishlist.vo.WishlistPageVO;
import com.travel.wishlist.vo.WishlistRemovedVO;

/** Wishlist business contract. Every method is scoped to the authenticated user. */
public interface WishlistService {

    WishlistPageVO list(Long userId, int page, int pageSize, boolean ascending);

    WishlistAddedVO add(Long userId, String googlePlaceId);

    WishlistRemovedVO remove(Long userId, String googlePlaceId);
}