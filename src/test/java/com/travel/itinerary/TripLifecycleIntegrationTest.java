package com.travel.itinerary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.travel.auth.repository.UserRepository;
import com.travel.itinerary.entity.Trip;
import com.travel.itinerary.entity.TripStatus;
import com.travel.itinerary.repository.TripRepository;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** POST /trips/{id}/start and /cancel over real HTTP, auth and the test database. */
@SpringBootTest
@AutoConfigureMockMvc
class TripLifecycleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired UserRepository userRepository;
    @Autowired TripRepository tripRepository;
    final JsonMapper json = JsonMapper.builder().build();

    static final String PASSWORD = "TravelPlanner!2026",
            OWNER = "owner@example.com",
            OTHER = "other@example.com",
            ORIGIN = "http://localhost:3000";

    String ownerToken;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        ownerToken = registerAndLogin(OWNER);
    }

    /** Removes everything this test creates so other modules' tests start from empty tables. */
    @AfterEach
    void clean() {
        String name = db.queryForObject("SELECT DATABASE()", String.class);
        if (!"travel_planner_test".equals(name))
            throw new IllegalStateException("Refusing to clean a non-test database");
        for (String table : List.of(
                "trip_pois", "trip_days", "trips",
                "auth_rate_limits", "auth_refresh_tokens", "auth_sessions", "users"))
            db.update("DELETE FROM " + table);
    }

    @Test
    void startMovesPlanningTripToOngoing() throws Exception {
        Long id = trip(OWNER, TripStatus.PLANNING);

        MockHttpServletResponse r = call("/trips/" + id + "/start", ownerToken);

        assertEquals(200, r.getStatus());
        JsonNode body = json.readTree(r.getContentAsString());
        assertEquals("success", body.get("message").asText());
        assertTrue(body.get("data").isNull());
        assertEquals("Ongoing", statusOf(id));
    }

    @Test
    void cancelMovesOngoingTripToCancelled() throws Exception {
        Long id = trip(OWNER, TripStatus.ONGOING);

        assertEquals(200, call("/trips/" + id + "/cancel", ownerToken).getStatus());
        assertEquals("Cancelled", statusOf(id));
    }

    @Test
    void startingAnOngoingTripIsRejected() throws Exception {
        Long id = trip(OWNER, TripStatus.ONGOING);

        assertError(call("/trips/" + id + "/start", ownerToken), 409, "INVALID_TRIP_STATUS");
        assertEquals("Ongoing", statusOf(id));
    }

    @Test
    void cancellingAPlanningTripIsRejected() throws Exception {
        Long id = trip(OWNER, TripStatus.PLANNING);

        assertError(call("/trips/" + id + "/cancel", ownerToken), 409, "INVALID_TRIP_STATUS");
        assertEquals("Planning", statusOf(id));
    }

    @Test
    void anotherUsersTripIsForbidden() throws Exception {
        Long id = trip(OWNER, TripStatus.PLANNING);
        String otherToken = registerAndLogin(OTHER);

        assertError(call("/trips/" + id + "/start", otherToken), 403, "FORBIDDEN");
        assertEquals("Planning", statusOf(id));
    }

    @Test
    void missingTripIsNotFound() throws Exception {
        assertError(call("/trips/999999/start", ownerToken), 404, "RESOURCE_NOT_FOUND");
    }

    @Test
    void requestWithoutTokenIsUnauthorized() throws Exception {
        Long id = trip(OWNER, TripStatus.PLANNING);

        assertError(call("/trips/" + id + "/start", null), 401, "AUTH_REQUIRED");
        assertEquals("Planning", statusOf(id));
    }

    // ---- helpers ----

    /** Saves a trip directly; POST /trips does not exist yet. */
    Long trip(String ownerEmail, TripStatus status) {
        Trip trip = new Trip();
        trip.setUser(userRepository.findByEmail(ownerEmail).orElseThrow());
        trip.setName("Hello New York");
        trip.setDayCount(1);
        trip.setStatus(status.value());
        return tripRepository.save(trip).getId();
    }

    String statusOf(Long tripId) {
        return db.queryForObject("SELECT status FROM trips WHERE id = ?", String.class, tripId);
    }

    /** POSTs with a CSRF token (required for every write) and, if given, a Bearer token. */
    MockHttpServletResponse call(String path, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(post(path));
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn().getResponse();
    }

    String registerAndLogin(String email) throws Exception {
        Map<String, String> credentials = Map.of("email", email, "password", PASSWORD);
        assertEquals(201, authPost("/auth/register", credentials).getStatus());
        MockHttpServletResponse login = authPost("/auth/login", credentials);
        assertEquals(200, login.getStatus());
        return json.readTree(login.getContentAsString()).get("data").get("accessToken").asText();
    }

    MockHttpServletResponse authPost(String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(post(path))
                .header("Origin", ORIGIN)
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType("application/json")
                .content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult handshake = mvc.perform(get("/auth/csrf")).andReturn();
        String token = json.readTree(handshake.getResponse().getContentAsString())
                .get("data").get("token").asText();
        return request.header("X-XSRF-TOKEN", token)
                .cookie(handshake.getResponse().getCookie("XSRF-TOKEN"));
    }

    void assertError(MockHttpServletResponse r, int status, String code) throws Exception {
        assertEquals(status, r.getStatus(), r.getContentAsString());
        assertEquals(code, json.readTree(r.getContentAsString()).get("error").get("code").asText());
    }
}
