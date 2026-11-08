package com.travel.wishlist.constant;

public final class WishlistConstants {
    private WishlistConstants() {}

    public static final int DEFAULT_PAGE_SIZE = 20;
    public static final int MAX_PAGE_SIZE = 100;

    // 文档只规定“超限返回 429 RATE_LIMITED”，具体阈值由本模块定义
    public static final int LIST_LIMIT = 120;
    public static final int LIST_WINDOW = 60;
    public static final int WRITE_LIMIT = 60;
    public static final int WRITE_WINDOW = 60;
}