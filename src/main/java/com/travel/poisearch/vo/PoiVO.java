package com.travel.poisearch.vo;

import java.util.List;

public record PoiVO(
        String googlePlaceId,
        String name,
        String address,
        Double latitude,
        Double longitude,
        String category,
        Double rating,
        String primaryType,
        List<String> types,
        Long userRatingCount,
        String businessStatus
) {
}
