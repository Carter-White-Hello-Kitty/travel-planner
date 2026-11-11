package com.travel.poisearch.client;

import com.sun.net.httpserver.HttpServer;
import com.travel.common.ApiError;
import java.net.*;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Base64;
import org.junit.jupiter.api.*;
import static org.junit.jupiter.api.Assertions.*;

class GoogleMapClientTest {
    HttpServer server;
    int status = 200;
    String type = "image/png", query;
    byte[] bytes = new byte[] {1, 2, 3};
    long delay;
    URI endpoint;
    @BeforeEach void setup() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/map", exchange -> {
            query = exchange.getRequestURI().getRawQuery();
            try {
                Thread.sleep(delay);
                exchange.getResponseHeaders().set("Content-Type", type);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        endpoint = URI.create("http://127.0.0.1:" + server.getAddress().getPort() + "/map");
    }
    GoogleMapClient client(Duration timeout) {
        return new GoogleMapClient("unit-test-only", HttpClient.newHttpClient(), endpoint, timeout);
    }
    @AfterEach void stop() { server.stop(0); }
    @Test void returnsImageWithoutCredentialUrl() {
        assertEquals("data:image/png;base64," + Base64.getEncoder().encodeToString(bytes),
                client(Duration.ofSeconds(2)).image(40, -75, 12));
        assertTrue(query.contains("center=40.0,-75.0&zoom=12"));
    }
    @Test void rejectsProviderErrorsAndUnexpectedContent() {
        for (int code : new int[] {400, 404, 403, 429, 500}) {
            status = code;
            var error = assertThrows(ApiError.class, () -> client(Duration.ofSeconds(2)).image(0, 0, 1));
            assertEquals(502, error.status.value());
            assertEquals("MAP_UNAVAILABLE", error.code);
            assertFalse(error.getMessage().contains("unit-test-only"));
        }
        status = 200; type = "text/html";
        assertEquals(502, assertThrows(ApiError.class, () -> client(Duration.ofSeconds(2)).image(0, 0, 1)).status.value());
        type = "image/png"; bytes = new byte[5_000_001];
        assertEquals(502, assertThrows(ApiError.class, () -> client(Duration.ofSeconds(2)).image(0, 0, 1)).status.value());
    }
    @Test void rejectsTimeoutMissingConfigurationAndConnectionFailure() {
        delay = 150;
        assertEquals(502, assertThrows(ApiError.class, () -> client(Duration.ofMillis(25)).image(0, 0, 1)).status.value());
        assertEquals(503, assertThrows(ApiError.class, () -> new GoogleMapClient("").image(0, 0, 1)).status.value());
        server.stop(0);
        assertEquals(502, assertThrows(ApiError.class, () -> client(Duration.ofSeconds(1)).image(0, 0, 1)).status.value());
    }
}
