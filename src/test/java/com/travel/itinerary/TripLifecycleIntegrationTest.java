package com.travel.itinerary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.travel.auth.repository.UserRepository;
import com.travel.auth.service.TokenService;
import com.travel.itinerary.entity.Trip;
import com.travel.itinerary.entity.TripDay;
import com.travel.itinerary.entity.TripPoi;
import com.travel.itinerary.entity.TripPoiStatus;
import com.travel.itinerary.entity.TripStatus;
import com.travel.itinerary.repository.TripRepository;
import com.travel.itinerary.service.TripService;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.vo.PoiVO;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import static org.mockito.Mockito.when;

/** Trip endpoints over the security filter chain and the test database. */
@SpringBootTest
@AutoConfigureMockMvc
class TripLifecycleIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired UserRepository userRepository;
    @Autowired TripRepository tripRepository;
    @Autowired TripService tripService;
    @MockitoBean GooglePlacesClient google;
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

    @Test
    void listsOnlyCurrentUsersTripsInDescendingIdOrder() throws Exception {
        Long older = trip(OWNER, TripStatus.PLANNING);
        registerAndLogin(OTHER);
        trip(OTHER, TripStatus.ONGOING);
        Long newer = trip(OWNER, TripStatus.COMPLETED);

        MockHttpServletResponse response = mvc.perform(get("/trips")
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse();

        assertEquals(200, response.getStatus());
        JsonNode body = json.readTree(response.getContentAsString());
        assertEquals("success", body.get("message").asText());
        JsonNode trips = body.get("data");
        assertEquals(2, trips.size());
        assertEquals(newer, trips.get(0).get("tripId").asLong());
        assertEquals("Completed", trips.get(0).get("status").asText());
        assertEquals("Hello New York", trips.get(0).get("name").asText());
        assertEquals(1, trips.get(0).get("dayCount").asInt());
        assertEquals(older, trips.get(1).get("tripId").asLong());
        assertFalse(trips.get(0).has("tripDays"));
    }

    @Test
    void listIsEmptyWhenCurrentUserHasNoTrips() throws Exception {
        registerAndLogin(OTHER);
        trip(OTHER, TripStatus.PLANNING);

        MockHttpServletResponse response = mvc.perform(get("/trips")
                .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse();

        assertEquals(200, response.getStatus());
        assertTrue(json.readTree(response.getContentAsString()).get("data").isEmpty());
    }

    @Test
    void listRequiresAuthentication() throws Exception {
        MockHttpServletResponse response = mvc.perform(get("/trips")).andReturn().getResponse();
        assertError(response, 401, "AUTH_REQUIRED");
    }

    @Test
    void detailShowsOrderedDaysPoisAndCurrentStatusForEveryTripState() throws Exception {
        stubPlaces();
        for (TripStatus status : TripStatus.values()) {
            Trip trip = tripWithPois(OWNER, status, TripPoiStatus.VISITED, TripPoiStatus.PLANNED);
            MockHttpServletResponse response = detail(trip.getId(), ownerToken);

            assertEquals(200, response.getStatus(), response.getContentAsString());
            JsonNode data = json.readTree(response.getContentAsString()).get("data");
            assertEquals(trip.getId(), data.get("tripId").asLong());
            assertEquals(status.value(), data.get("status").asText());
            assertEquals("2026-12-01", data.get("startDate").asText());
            assertEquals(2, data.get("dayCount").asInt());
            assertEquals(1, data.get("tripDays").get(0).get("dayNumber").asInt());
            assertEquals(2, data.get("tripDays").get(1).get("dayNumber").asInt());
            JsonNode firstDayPois = data.get("tripDays").get(0).get("tripPois");
            assertEquals("place-a", firstDayPois.get(0).get("googlePlaceId").asText());
            assertEquals("Visited", firstDayPois.get(0).get("status").asText());
            assertEquals("Museum A", firstDayPois.get(0).get("name").asText());
            assertEquals(40.7794, firstDayPois.get(0).get("latitude").asDouble());
            assertEquals("place-b", firstDayPois.get(1).get("googlePlaceId").asText());
            assertEquals("Planned", firstDayPois.get(1).get("status").asText());
            assertEquals("place-c", data.get("tripDays").get(1).get("tripPois")
                    .get(0).get("googlePlaceId").asText());
        }
    }

    @Test
    void detailRejectsOtherUsersAndMissingTrips() throws Exception {
        Trip trip = tripWithPois(OWNER, TripStatus.PLANNING,
                TripPoiStatus.PLANNED, TripPoiStatus.PLANNED);
        String otherToken = registerAndLogin(OTHER);

        assertError(detail(trip.getId(), otherToken), 404, "RESOURCE_NOT_FOUND");
        assertError(detail(999999L, ownerToken), 404, "RESOURCE_NOT_FOUND");
        assertError(detail(trip.getId(), null), 401, "AUTH_REQUIRED");
    }

    @Test
    void checkInRequiresOngoingAndLastPoiCompletesTrip() throws Exception {
        stubPlaces();
        Trip trip = tripWithPois(OWNER, TripStatus.PLANNING,
                TripPoiStatus.PLANNED, TripPoiStatus.PLANNED);
        Long firstPoiId = trip.getTripDays().get(1).getTripPois().get(1).getId();
        Long secondPoiId = trip.getTripDays().get(1).getTripPois().get(0).getId();
        Long lastPoiId = trip.getTripDays().get(0).getTripPois().get(0).getId();

        assertError(call("/trips/" + firstPoiId + "/check-in", ownerToken),
                409, "INVALID_TRIP_STATUS");
        assertEquals("Planned", poiStatusOf(firstPoiId));
        assertEquals(200, call("/trips/" + trip.getId() + "/start", ownerToken).getStatus());

        assertCheckIn(firstPoiId, "Ongoing");
        assertCheckIn(firstPoiId, "Ongoing"); // Response lost: retry cannot advance twice.
        assertCheckIn(secondPoiId, "Ongoing");
        assertCheckIn(lastPoiId, "Completed");
        assertCheckIn(lastPoiId, "Completed"); // Retry after auto-completion is still safe.

        assertEquals("Completed", statusOf(trip.getId()));
        assertEquals("Visited", poiStatusOf(firstPoiId));
        assertEquals("Visited", poiStatusOf(secondPoiId));
        assertEquals("Visited", poiStatusOf(lastPoiId));
        JsonNode detail = json.readTree(detail(trip.getId(), ownerToken).getContentAsString()).get("data");
        assertEquals("Completed", detail.get("status").asText());
        assertEquals("Visited", detail.get("tripDays").get(1).get("tripPois")
                .get(0).get("status").asText());
        assertError(call("/trips/" + trip.getId() + "/cancel", ownerToken),
                409, "INVALID_TRIP_STATUS");
    }

    @Test
    void checkInAcceptsPassedButRejectsCancelledForeignMissingAndAnonymous() throws Exception {
        Trip passedTrip = tripWithPois(OWNER, TripStatus.ONGOING,
                TripPoiStatus.PASSED, TripPoiStatus.VISITED);
        Long passedPoiId = passedTrip.getTripDays().get(1).getTripPois().get(1).getId();
        Long remainingPoiId = passedTrip.getTripDays().get(0).getTripPois().get(0).getId();
        assertCheckIn(passedPoiId, "Ongoing");
        assertCheckIn(remainingPoiId, "Completed");

        Trip cancelled = tripWithPois(OWNER, TripStatus.CANCELLED,
                TripPoiStatus.PLANNED, TripPoiStatus.PLANNED);
        Long cancelledPoiId = cancelled.getTripDays().get(1).getTripPois().get(1).getId();
        assertError(call("/trips/" + cancelledPoiId + "/check-in", ownerToken),
                409, "INVALID_TRIP_STATUS");
        assertEquals("Planned", poiStatusOf(cancelledPoiId));

        String otherToken = registerAndLogin(OTHER);
        assertError(call("/trips/" + cancelledPoiId + "/check-in", otherToken), 403, "FORBIDDEN");
        assertError(call("/trips/999999/check-in", ownerToken), 404, "RESOURCE_NOT_FOUND");
        assertError(call("/trips/" + cancelledPoiId + "/check-in", null), 401, "AUTH_REQUIRED");
    }

    @Test
    void concurrentCheckInsOfLastTwoPoisCompleteTheTripOnce() throws Exception {
        Trip trip = tripWithPois(OWNER, TripStatus.ONGOING,
                TripPoiStatus.PLANNED, TripPoiStatus.PLANNED);
        Long firstPoiId = trip.getTripDays().get(1).getTripPois().get(1).getId();
        Long secondPoiId = trip.getTripDays().get(1).getTripPois().get(0).getId();
        Long alreadyVisitedId = trip.getTripDays().get(0).getTripPois().get(0).getId();
        db.update("UPDATE trip_pois SET status = 'Visited' WHERE id = ?", alreadyVisitedId);
        Long userId = userRepository.findByEmail(OWNER).orElseThrow().getId();
        var identity = new TokenService.Identity(userId, UUID.randomUUID());
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> {
                start.await();
                return tripService.checkIn(identity, firstPoiId);
            });
            var second = pool.submit(() -> {
                start.await();
                return tripService.checkIn(identity, secondPoiId);
            });
            start.countDown();

            var firstResult = first.get(10, TimeUnit.SECONDS);
            var secondResult = second.get(10, TimeUnit.SECONDS);
            assertEquals("Visited", firstResult.poiStatus());
            assertEquals("Visited", secondResult.poiStatus());
            assertTrue(List.of(firstResult.tripStatus(), secondResult.tripStatus())
                    .contains("Completed"));
            assertEquals("Completed", statusOf(trip.getId()));
            assertEquals("Visited", poiStatusOf(firstPoiId));
            assertEquals("Visited", poiStatusOf(secondPoiId));
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void generationRejectsInvalidRequestBeforeSaving() throws Exception {
        MockHttpServletResponse response = mvc.perform(withCsrf(post("/trips"))
                .header("Authorization", "Bearer " + ownerToken)
                .contentType("application/json")
                .content("""
                        {"name":"Invalid trip","startDate":"2026-12-01","dayCount":0,
                         "searchArea":{"latitude":40.758,"longitude":-73.9855,"radiusMeters":15000},
                         "constraints":{"maxPoisPerDay":3,"preferredTypes":[]}}
                        """)).andReturn().getResponse();

        assertError(response, 400, "INVALID_REQUEST");
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM trips", Long.class));
    }

    // ---- helpers ----

    /** Saves a trip directly so lifecycle tests do not depend on Google Places. */
    Long trip(String ownerEmail, TripStatus status) {
        Trip trip = new Trip();
        trip.setUser(userRepository.findByEmail(ownerEmail).orElseThrow());
        trip.setName("Hello New York");
        trip.setDayCount(1);
        trip.setStatus(status);
        return tripRepository.save(trip).getId();
    }

    String statusOf(Long tripId) {
        return db.queryForObject("SELECT status FROM trips WHERE id = ?", String.class, tripId);
    }

    String poiStatusOf(Long tripPoiId) {
        return db.queryForObject("SELECT status FROM trip_pois WHERE id = ?", String.class, tripPoiId);
    }

    Trip tripWithPois(String ownerEmail, TripStatus status,
            TripPoiStatus firstPoiStatus, TripPoiStatus secondPoiStatus) {
        Trip trip = new Trip();
        trip.setUser(userRepository.findByEmail(ownerEmail).orElseThrow());
        trip.setName("Hello New York");
        trip.setStartDate(LocalDate.of(2026, 12, 1));
        trip.setDayCount(2);
        trip.setStatus(status);

        TripDay dayTwo = new TripDay();
        dayTwo.setDayNumber(2);
        trip.addDay(dayTwo);
        TripPoi third = new TripPoi();
        third.setGooglePlaceId("place-c");
        third.setSequence(1);
        dayTwo.addPoi(third);

        TripDay dayOne = new TripDay();
        dayOne.setDayNumber(1);
        trip.addDay(dayOne);
        TripPoi second = new TripPoi();
        second.setGooglePlaceId("place-b");
        second.setSequence(2);
        second.setStatus(secondPoiStatus);
        dayOne.addPoi(second);
        TripPoi first = new TripPoi();
        first.setGooglePlaceId("place-a");
        first.setSequence(1);
        first.setStatus(firstPoiStatus);
        dayOne.addPoi(first);
        return tripRepository.saveAndFlush(trip);
    }

    void stubPlaces() {
        when(google.details("place-a")).thenReturn(place("place-a", "Museum A", 40.7794));
        when(google.details("place-b")).thenReturn(place("place-b", "Park B", 40.774));
        when(google.details("place-c")).thenReturn(place("place-c", "Gallery C", 40.75));
    }

    PoiVO place(String id, String name, double latitude) {
        return new PoiVO(id, name, "New York, NY, USA", latitude, -73.9632,
                "museum", 4.5, "museum", List.of("museum"), 100L, "OPERATIONAL");
    }

    MockHttpServletResponse detail(Long tripId, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = get("/trips/" + tripId);
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn().getResponse();
    }

    void assertCheckIn(Long tripPoiId, String expectedTripStatus) throws Exception {
        MockHttpServletResponse response = call("/trips/" + tripPoiId + "/check-in", ownerToken);
        assertEquals(200, response.getStatus(), response.getContentAsString());
        JsonNode data = json.readTree(response.getContentAsString()).get("data");
        assertEquals(tripPoiId, data.get("tripPoiId").asLong());
        assertEquals("Visited", data.get("poiStatus").asText());
        assertEquals(expectedTripStatus, data.get("tripStatus").asText());
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
