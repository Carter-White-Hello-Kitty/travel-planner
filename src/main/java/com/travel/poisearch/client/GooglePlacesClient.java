package com.travel.poisearch.client;

import com.travel.common.ApiError;
import com.travel.poisearch.vo.PoiSearchRequest;
import com.travel.poisearch.vo.PoiVO;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@Component
public class GooglePlacesClient {
  private static final URI ENDPOINT = URI.create("https://places.googleapis.com/v1/places:searchText");
  static final String FIELD_MASK = "places.id,places.displayName,places.formattedAddress,"
      + "places.location,places.primaryType,places.types,places.rating,"
      + "places.userRatingCount,places.businessStatus";
  static final String DETAILS_FIELD_MASK = "id,displayName,formattedAddress,location,"
      + "primaryType,types,rating,userRatingCount,businessStatus";
  private final HttpClient http;
  private final URI endpoint;
  private final String apiKey;
  private final Duration timeout;
  private final JsonMapper json = JsonMapper.builder().build();

  @Autowired
  public GooglePlacesClient(
      @Value("${google.places.api-key:}") String apiKey,
      @Value("${google.places.connect-timeout:2s}") Duration connectTimeout,
      @Value("${google.places.request-timeout:5s}") Duration timeout) {
    this(HttpClient.newBuilder().connectTimeout(connectTimeout)
        .followRedirects(HttpClient.Redirect.NEVER).build(), ENDPOINT, apiKey, timeout);
  }

  // Package-private endpoint injection keeps production credentials on Google's fixed HTTPS host.
  GooglePlacesClient(HttpClient http, URI endpoint, String apiKey, Duration timeout) {
    this.http = http;
    this.endpoint = endpoint;
    this.apiKey = apiKey;
    this.timeout = timeout;
  }

  public List<PoiVO> search(PoiSearchRequest request) {
    if (apiKey == null || apiKey.isBlank())
      throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "POI_SEARCH_NOT_CONFIGURED",
          "POI search is not configured");
    try {
      String body = json.writeValueAsString(Map.of(
          "textQuery", request.keyword(), "pageSize", 20,
          "locationBias", Map.of("circle", Map.of(
              "center", Map.of("latitude", request.latitude(), "longitude", request.longitude()),
              "radius", request.radius()))));
      HttpRequest call = HttpRequest.newBuilder(endpoint).timeout(timeout)
          .header("Content-Type", "application/json")
          .header("X-Goog-Api-Key", apiKey)
          .header("X-Goog-FieldMask", FIELD_MASK)
          .POST(HttpRequest.BodyPublishers.ofString(body)).build();
      HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw upstreamError();
      return decode(response.body());
    } catch (HttpTimeoutException e) {
      throw new ApiError(HttpStatus.GATEWAY_TIMEOUT, "POI_SEARCH_TIMEOUT", "POI search timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw upstreamError();
    } catch (IOException e) {
      throw upstreamError();
    } catch (ApiError e) {
      throw e;
    } catch (RuntimeException e) {
      // Do not expose upstream bodies, headers, credentials or exception messages.
      throw upstreamError();
    }
  }

  /** Loads a user-selected place so itinerary generation never trusts client-supplied coordinates. */
  public PoiVO details(String placeId) {
    if (placeId == null || placeId.isBlank() || placeId.length() > 225)
      throw ApiError.invalid("googlePlaceId", "Required Google Place ID, at most 225 characters");
    if (apiKey == null || apiKey.isBlank())
      throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "POI_SEARCH_NOT_CONFIGURED",
          "POI search is not configured");
    try {
      URI uri = endpoint.resolve("places/" + URLEncoder.encode(placeId, StandardCharsets.UTF_8));
      HttpRequest call = HttpRequest.newBuilder(uri).timeout(timeout)
          .header("X-Goog-Api-Key", apiKey)
          .header("X-Goog-FieldMask", DETAILS_FIELD_MASK)
          .GET().build();
      HttpResponse<String> response = http.send(call, HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 404)
        throw ApiError.invalid("googlePlaceId", "Google Place ID not found");
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw upstreamError();
      JsonNode place = json.readTree(response.body());
      if (place == null || !place.isObject() || place.has("error")) throw upstreamError();
      PoiVO result = decodePlace(place);
      if (!placeId.equals(result.googlePlaceId())) throw upstreamError();
      return result;
    } catch (HttpTimeoutException e) {
      throw new ApiError(HttpStatus.GATEWAY_TIMEOUT, "POI_SEARCH_TIMEOUT", "POI search timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw upstreamError();
    } catch (IOException e) {
      throw upstreamError();
    } catch (ApiError e) {
      throw e;
    } catch (RuntimeException e) {
      throw upstreamError();
    }
  }

  private List<PoiVO> decode(String body) {
    JsonNode root = json.readTree(body);
    if (root == null || !root.isObject() || root.has("error")) throw upstreamError();
    JsonNode places = root.get("places");
    // Google's protobuf JSON omits repeated fields when there are no results.
    if (places == null) return List.of();
    if (!places.isArray()) throw upstreamError();
    List<PoiVO> result = new ArrayList<>();
    for (JsonNode place : places) {
      result.add(decodePlace(place));
    }
    return List.copyOf(result);
  }

  private PoiVO decodePlace(JsonNode place) {
    String id = text(place, "id");
    JsonNode location = place.path("location");
    Double lat = number(location, "latitude"), lon = number(location, "longitude");
    if (id == null || lat == null || lon == null || Math.abs(lat) > 90 || Math.abs(lon) > 180)
      throw upstreamError();
    String primaryType = text(place, "primaryType");
    List<String> types = types(place);
    String category = primaryType != null ? primaryType : types.isEmpty() ? null : types.get(0);
    return new PoiVO(null, id, text(place.path("displayName"), "text"),
        text(place, "formattedAddress"), lat, lon, category, number(place, "rating"),
        primaryType, types, count(place, "userRatingCount"), text(place, "businessStatus"));
  }

  private static String text(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value != null && value.isString() ? value.asString() : null;
  }

  private static Double number(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null || !value.isNumber()) return null;
    double n = value.asDouble();
    if (!Double.isFinite(n)) throw upstreamError();
    return n;
  }

  private static List<String> types(JsonNode place) {
    JsonNode values = place.get("types");
    if (values == null) return List.of();
    if (!values.isArray()) throw upstreamError();
    List<String> result = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isString()) throw upstreamError();
      result.add(value.asString());
    }
    return List.copyOf(result);
  }

  private static Long count(JsonNode node, String field) {
    JsonNode value = node.get(field);
    if (value == null) return null;
    if (!value.isIntegralNumber() || !value.canConvertToLong() || value.asLong() < 0)
      throw upstreamError();
    return value.asLong();
  }

  private static ApiError upstreamError() {
    return new ApiError(HttpStatus.BAD_GATEWAY, "POI_SEARCH_UPSTREAM_ERROR",
        "POI search provider is temporarily unavailable");
  }
}
