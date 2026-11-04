package com.travel.poisearch.service.impl;

import com.travel.common.ApiError;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.service.PoiSearchService;
import com.travel.poisearch.vo.PoiSearchRequest;
import com.travel.poisearch.vo.PoiVO;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class PoiSearchServiceImpl implements PoiSearchService {
  private final GooglePlacesClient google;

  public PoiSearchServiceImpl(GooglePlacesClient google) {
    this.google = google;
  }

  @Override
  public List<PoiVO> search(PoiSearchRequest request) {
    if (request == null) throw ApiError.invalid("body", "Required object");
    if (request.keyword() == null || request.keyword().isBlank()
        || request.keyword().length() > 200)
      throw ApiError.invalid("keyword", "Required nonblank string, at most 200 characters");
    coordinate(request.latitude(), 90, "latitude");
    coordinate(request.longitude(), 180, "longitude");
    if (request.radius() == null || request.radius() < 1 || request.radius() > 50000)
      throw ApiError.invalid("radius", "Must be an integer from 1 to 50000 meters");
    PoiSearchRequest normalized = new PoiSearchRequest(
        request.keyword().strip(), request.latitude(), request.longitude(), request.radius());
    // Bias is not a restriction: enforce the requested circle locally for every result.
    return google.search(normalized).stream()
        .filter(p -> p.latitude() != null && p.longitude() != null)
        .filter(p -> distanceMeters(request.latitude(), request.longitude(),
            p.latitude(), p.longitude()) <= request.radius())
        .toList();
  }

  private static void coordinate(Double value, int limit, String field) {
    if (value == null || !Double.isFinite(value) || value < -limit || value > limit)
      throw ApiError.invalid(field, "Must be a finite number between " + -limit + " and " + limit);
  }

  static double distanceMeters(double lat1, double lon1, double lat2, double lon2) {
    double dLat = Math.toRadians(lat2 - lat1);
    double dLon = Math.toRadians(lon2 - lon1);
    double a = Math.pow(Math.sin(dLat / 2), 2)
        + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
        * Math.pow(Math.sin(dLon / 2), 2);
    return 6371008.8 * 2 * Math.asin(Math.sqrt(Math.min(1, Math.max(0, a))));
  }
}
