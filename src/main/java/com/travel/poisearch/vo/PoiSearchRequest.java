package com.travel.poisearch.vo;

public record PoiSearchRequest(
        String keyword,
        Double latitude,
        Double longitude,
        Integer radius
) {}
