package com.travel.vo;

public record PoiVO(
        Long id,
        String googlePlaceId,
        String name,
        String address,
        Double latitude,
        Double longitude,
        String category,
        Double rating
) {
}
