package com.travel.poisearch.client;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import com.travel.common.ApiError;
import com.travel.poisearch.vo.PoiSearchRequest;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.*;
import tools.jackson.databind.json.JsonMapper;

class GooglePlacesClientTest {
  HttpServer server;
  GooglePlacesClient client;
  volatile int status = 200;
  volatile String body = "{}", requestBody, fieldMask, key;
  volatile long delay;
  final PoiSearchRequest request = new PoiSearchRequest("museum", 39.3299, -76.6205, 5000);

  @BeforeEach
  void setup() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    server.createContext("/search", exchange -> {
      requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
      fieldMask = exchange.getRequestHeaders().getFirst("X-Goog-FieldMask");
      key = exchange.getRequestHeaders().getFirst("X-Goog-Api-Key");
      try {
        Thread.sleep(delay);
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      } finally {
        exchange.close();
      }
    });
    server.start();
    client = client("fake-test-key", Duration.ofSeconds(2));
  }

  GooglePlacesClient client(String key, Duration timeout) {
    return new GooglePlacesClient(HttpClient.newHttpClient(),
        URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/search"), key, timeout);
  }

  @AfterEach
  void cleanup() { server.stop(0); }

  @Test
  void sendsNewApiRequestAndMapsFieldsWithoutDatabaseId() {
    body = """
        {"places":[{"id":"google-id","displayName":{"text":"Museum"},
        "formattedAddress":"Baltimore","location":{"latitude":39.33,"longitude":-76.62},
        "primaryType":"museum","rating":4.7}]}
        """;
    var places = client.search(request);
    var sent = JsonMapper.builder().build().readTree(requestBody);
    assertEquals("museum", sent.path("textQuery").asString());
    assertEquals(5000, sent.path("locationBias").path("circle").path("radius").asInt());
    assertEquals(39.3299, sent.path("locationBias").path("circle").path("center").path("latitude").asDouble());
    assertEquals(20, sent.path("pageSize").asInt());
    assertEquals(GooglePlacesClient.FIELD_MASK, fieldMask);
    assertEquals("fake-test-key", key);
    assertNull(places.get(0).id());
    assertEquals("google-id", places.get(0).googlePlaceId());
    assertEquals("Museum", places.get(0).name());
    assertEquals("Baltimore", places.get(0).address());
    assertEquals("museum", places.get(0).category());
    assertEquals(4.7, places.get(0).rating());
  }

  @Test
  void acceptsEmptyResultsAndOptionalFields() {
    assertTrue(client.search(request).isEmpty());
    body = "{\"places\":[]}";
    assertTrue(client.search(request).isEmpty());
    body = """
        {"places":[{"id":"x","location":{"latitude":0,"longitude":0},"types":["park"]}]}
        """;
    var place = client.search(request).get(0);
    assertEquals("park", place.category());
    assertNull(place.rating());
  }

  @Test
  void rejectsHttpErrorsAndMalformedResponsesInsteadOfReturningEmpty() {
    for (int code : new int[] {400, 403, 429, 500, 503}) {
      status = code;
      ApiError error = assertThrows(ApiError.class, () -> client.search(request));
      assertEquals(502, error.status.value());
      assertFalse(error.getMessage().contains("fake-test-key"));
    }
    status = 200;
    for (String invalid : new String[] {"not-json", "null", "[]", "{\"error\":{}}",
        "{\"places\":null}", "{\"places\":[{\"id\":\"x\"}]}"}) {
      body = invalid;
      assertEquals("POI_SEARCH_UPSTREAM_ERROR",
          assertThrows(ApiError.class, () -> client.search(request)).code);
    }
  }

  @Test
  void handlesTimeoutMissingKeyAndConnectionFailure() {
    assertEquals(503, assertThrows(ApiError.class,
        () -> client("", Duration.ofSeconds(1)).search(request)).status.value());
    delay = 500;
    assertEquals(504, assertThrows(ApiError.class,
        () -> client("fake-test-key", Duration.ofMillis(100)).search(request)).status.value());
    server.stop(0);
    assertEquals(502, assertThrows(ApiError.class, () -> client.search(request)).status.value());
  }
}
