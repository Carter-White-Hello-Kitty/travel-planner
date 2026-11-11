package com.travel.poisearch.vo;

import java.util.List;

public record PlaceDetails(PoiVO place, List<Photo> photos, String website,
        String phone, List<String> openingHours) {
    public record Photo(String name, List<Author> authors) {}
    public record Author(String name, String uri) {}
}
