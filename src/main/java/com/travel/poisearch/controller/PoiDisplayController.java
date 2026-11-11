package com.travel.poisearch.controller;

import com.travel.common.Result;
import com.travel.common.ApiError;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.client.GoogleMapClient;
import com.travel.poisearch.vo.PlaceDetails;
import java.util.Map;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/pois")
public class PoiDisplayController {
    private final GooglePlacesClient places;
    private final GoogleMapClient maps;
    public PoiDisplayController(GooglePlacesClient places, GoogleMapClient maps) {
        this.places = places; this.maps = maps;
    }
    @GetMapping("/{id}")
    public Result<PlaceDetails> details(@PathVariable String id) {
        return Result.success(places.displayDetails(id));
    }
    @PostMapping("/photo")
    public Result<Map<String, String>> photo(@RequestBody Map<String, String> body) {
        return Result.success(Map.of("url", places.photoUrl(body.get("name"))));
    }
    public record MapRequest(Double latitude, Double longitude, Integer zoom) {}
    @PostMapping("/map")
    public Result<Map<String, String>> map(@RequestBody MapRequest body) {
        if (body.latitude() == null || !Double.isFinite(body.latitude()) || Math.abs(body.latitude()) > 85
                || body.longitude() == null || !Double.isFinite(body.longitude()) || Math.abs(body.longitude()) > 180
                || body.zoom() == null || body.zoom() < 1 || body.zoom() > 19)
            throw ApiError.invalid("viewport", "Invalid map coordinates or zoom");
        return Result.success(Map.of("image", maps.image(body.latitude(), body.longitude(), body.zoom())));
    }
}
