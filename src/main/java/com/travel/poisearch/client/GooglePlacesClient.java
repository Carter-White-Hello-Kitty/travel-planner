package com.travel.poisearch.client;

import com.travel.common.ApiError;
import com.travel.poisearch.vo.PoiSearchRequest;
import com.travel.poisearch.vo.PoiVO;
import com.travel.poisearch.vo.PlaceDetails;
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
    if (placeId == null || !placeId.matches("[A-Za-z0-9_-]{1,225}"))
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
      if (response.statusCode() == 400 || response.statusCode() == 404)
        throw resourceError(response.statusCode(), false);
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
    } catch (ApiError e) {
      throw e;
    } catch (IOException | RuntimeException e) {
      throw upstreamError();
    }
  }

  /** Extended display fields, kept separate from the established search contract. */
  public PlaceDetails displayDetails(String placeId) {
    // Reuse existing ID validation and fail closed when no server credential is configured.
    if (placeId == null || !placeId.matches("[A-Za-z0-9_-]{1,225}"))
      throw ApiError.invalid("googlePlaceId", "Invalid Google Place ID");
    JsonNode node = getJson(endpoint.resolve("places/" + placeId),
        DETAILS_FIELD_MASK + ",photos,websiteUri,nationalPhoneNumber,regularOpeningHours");
    PoiVO place = decodePlace(node);
    if (!placeId.equals(place.googlePlaceId())) throw upstreamError();
    List<PlaceDetails.Photo> photos = new ArrayList<>();
    for (JsonNode photo : node.path("photos")) {
      List<PlaceDetails.Author> authors = new ArrayList<>();
      for (JsonNode author : photo.path("authorAttributions"))
        authors.add(new PlaceDetails.Author(text(author, "displayName"), text(author, "uri")));
      String name = text(photo, "name");
      if (name != null) photos.add(new PlaceDetails.Photo(name, List.copyOf(authors)));
      if (photos.size() == 3) break;
    }
    List<String> hours = new ArrayList<>();
    for (JsonNode day : node.path("regularOpeningHours").path("weekdayDescriptions"))
      if (day.isString()) hours.add(day.asString());
    return new PlaceDetails(place, List.copyOf(photos), text(node, "websiteUri"),
        text(node, "nationalPhoneNumber"), List.copyOf(hours));
  }

  /** Only the short-lived Google media URL is returned; never a credential-bearing URL. */
  public String photoUrl(String name) {
    if (name == null || !name.matches("places/[A-Za-z0-9_-]{1,225}/photos/[A-Za-z0-9_-]{1,2000}"))
      throw ApiError.invalid("name", "Invalid photo resource");
    JsonNode media = getJson(endpoint.resolve(name + "/media?maxWidthPx=800&skipHttpRedirect=true"), null);
    String url = text(media, "photoUri");
    if (url == null) throw upstreamError();
    URI uri;
    try { uri = URI.create(url); } catch (IllegalArgumentException e) { throw upstreamError(); }
    String host = uri.getHost();
    if (!"https".equals(uri.getScheme()) || host == null || uri.getUserInfo() != null
        || !(host.equals("googleusercontent.com") || host.endsWith(".googleusercontent.com")))
      throw upstreamError();
    return url;
  }

  private JsonNode getJson(URI uri, String mask) {
    if (apiKey == null || apiKey.isBlank())
      throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "POI_SEARCH_NOT_CONFIGURED", "POI search is not configured");
    try {
      HttpRequest.Builder call = HttpRequest.newBuilder(uri).timeout(timeout).header("X-Goog-Api-Key", apiKey);
      if (mask != null) call.header("X-Goog-FieldMask", mask);
      HttpResponse<String> response = http.send(call.GET().build(), HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() == 400 || response.statusCode() == 404)
        throw resourceError(response.statusCode(), uri.getPath().contains("/photos/"));
      if (response.statusCode() < 200 || response.statusCode() >= 300) throw upstreamError();
      JsonNode node = json.readTree(response.body());
      if (node == null || !node.isObject() || node.has("error")) throw upstreamError();
      return node;
    } catch (HttpTimeoutException e) {
      throw new ApiError(HttpStatus.GATEWAY_TIMEOUT, "POI_SEARCH_TIMEOUT", "Place provider timed out");
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt(); throw upstreamError();
    } catch (ApiError e) { throw e;
    } catch (IOException | RuntimeException e) { throw upstreamError(); }
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
    return new PoiVO(id, text(place.path("displayName"), "text"),
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

  /** Only explicit resource/request failures cross the API boundary; upstream text never does. */
  private static ApiError resourceError(int status, boolean photo) {
    if (status == 404)
      return new ApiError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND",
          photo ? "Photo not found" : "Place not found");
    return ApiError.invalid(photo ? "name" : "googlePlaceId",
        photo ? "Invalid photo resource" : "Invalid Google Place ID");
  }

  private static ApiError upstreamError() {
    return new ApiError(HttpStatus.BAD_GATEWAY, "POI_SEARCH_UPSTREAM_ERROR",
        "POI search provider is temporarily unavailable");
  }
}
