package com.travel.wishlist.vo;

import java.util.List;

public record WishlistPageVO(List<WishlistItemVO> items, Pagination pagination) {

    public record Pagination(int page, int pageSize, long totalItems, int totalPages) {
    }
}