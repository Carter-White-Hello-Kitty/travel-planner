package com.travel.poisearch.client;

import com.travel.common.ApiError;
import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.net.http.*;
import java.time.Duration;
import java.util.Base64;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

@Component
public class GoogleMapClient {
    private final String key;
    private final HttpClient http;
    private final URI endpoint;
    private final Duration timeout;
    @Autowired
    public GoogleMapClient(@Value("${google.maps.api-key:${google.places.api-key:}}") String key) {
        this(key, HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build(),
                URI.create("https://maps.googleapis.com/maps/api/staticmap"), Duration.ofSeconds(8));
    }
    // Local HTTP injection for tests; production always uses the fixed Google endpoint.
    GoogleMapClient(String key, HttpClient http, URI endpoint, Duration timeout) {
        this.key = key; this.http = http; this.endpoint = endpoint; this.timeout = timeout;
    }
    public String image(double latitude, double longitude, int zoom) {
        if (key == null || key.isBlank())
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "MAP_NOT_CONFIGURED", "Google map is not configured");
        try {
            URI uri = URI.create(endpoint + "?center="
                    + latitude + "," + longitude + "&zoom=" + zoom
                    + "&size=640x480&format=png&key=" + URLEncoder.encode(key, StandardCharsets.UTF_8));
            HttpResponse<byte[]> response = http.send(HttpRequest.newBuilder(uri)
                    .timeout(timeout).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() != 200 || !response.headers().firstValue("Content-Type")
                    .orElse("").startsWith("image/png") || response.body().length > 5000000)
                throw unavailable();
            return "data:image/png;base64," + Base64.getEncoder().encodeToString(response.body());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (java.io.IOException e) { throw unavailable(); }
    }
    private static ApiError unavailable() {
        return new ApiError(HttpStatus.BAD_GATEWAY, "MAP_UNAVAILABLE", "Google map unavailable. Check Maps Static API configuration.");
    }
}
