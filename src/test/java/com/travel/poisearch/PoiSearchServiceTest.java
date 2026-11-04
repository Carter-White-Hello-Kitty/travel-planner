package com.travel.poisearch;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.travel.common.ApiError;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.service.impl.PoiSearchServiceImpl;
import com.travel.poisearch.vo.*;
import java.util.List;
import org.junit.jupiter.api.Test;

class PoiSearchServiceTest {
  final GooglePlacesClient google = mock(GooglePlacesClient.class);
  final PoiSearchServiceImpl service = new PoiSearchServiceImpl(google);

  PoiVO place(String id, double lat, double lon) {
    return new PoiVO(null, id, "Museum", "Address", lat, lon, "museum", null,
        "museum", List.of("museum", "tourist_attraction"), null, "OPERATIONAL");
  }

  @Test
  void filtersCircleIncludingRectangleCornersAndKeepsCenter() {
    var request = new PoiSearchRequest(" museum ", 0.0, 0.0, 1000);
    when(google.search(any())).thenReturn(List.of(place("center", 0, 0),
        place("inside", .006, .006), place("outside", .009, .009),
        place("far", 1, 1)));
    assertEquals(List.of("center", "inside"), service.search(request).stream().map(PoiVO::googlePlaceId).toList());
    verify(google).search(new PoiSearchRequest("museum", 0.0, 0.0, 1000));
  }

  @Test
  void handlesDateLinePolesAndEmptyResults() {
    when(google.search(any())).thenReturn(List.of(place("across", 0, -179.999)));
    assertEquals(1, service.search(new PoiSearchRequest("x", 0.0, 179.999, 500)).size());
    when(google.search(any())).thenReturn(List.of(place("pole", 90, 170)));
    assertEquals(1, service.search(new PoiSearchRequest("x", 90.0, -170.0, 1)).size());
    when(google.search(any())).thenReturn(List.of());
    assertTrue(service.search(new PoiSearchRequest("x", 0.0, 0.0, 1)).isEmpty());
  }

  @Test
  void validatesBeforeCallingGoogle() {
    for (PoiSearchRequest invalid : new PoiSearchRequest[] {null,
        new PoiSearchRequest(null, 0.0, 0.0, 1), new PoiSearchRequest(" ", 0.0, 0.0, 1),
        new PoiSearchRequest("x".repeat(201), 0.0, 0.0, 1),
        new PoiSearchRequest("x", null, 0.0, 1), new PoiSearchRequest("x", 91.0, 0.0, 1),
        new PoiSearchRequest("x", Double.NaN, 0.0, 1),
        new PoiSearchRequest("x", 0.0, Double.POSITIVE_INFINITY, 1),
        new PoiSearchRequest("x", 0.0, -181.0, 1), new PoiSearchRequest("x", 0.0, null, 1),
        new PoiSearchRequest("x", 0.0, 0.0, null), new PoiSearchRequest("x", 0.0, 0.0, 0),
        new PoiSearchRequest("x", 0.0, 0.0, 50001)}) {
      assertEquals(400, assertThrows(ApiError.class, () -> service.search(invalid)).status.value());
    }
    verifyNoInteractions(google);
  }
}
