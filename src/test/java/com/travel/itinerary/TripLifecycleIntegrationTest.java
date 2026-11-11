package com.travel.itinerary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.travel.auth.repository.UserRepository;
import com.travel.auth.config.Secrets;
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
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
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
    @Autowired Secrets secrets;
    @Autowired UserRepository userRepository;
    @Autowired TripRepository tripRepository;
    @Autowired TripService tripService;
    @Autowired com.travel.itinerary.service.ManualTripService manualTrips;
    @Autowired StringRedisTemplate redis;
    @MockitoBean GooglePlacesClient google;
    @org.springframework.test.context.bean.override.mockito.MockitoSpyBean
    com.travel.itinerary.repository.TripPoiRepository tripPois;
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

    /** Removes only the two accounts and trips owned by this test class. */
    @AfterEach
    void clean() {
        String name = db.queryForObject("SELECT DATABASE()", String.class);
        if (!"travel_planner_test".equals(name))
            throw new IllegalStateException("Refusing to clean a non-test database");
        for (Long userId : db.queryForList(
                "SELECT id FROM users WHERE email IN (?, ?)", Long.class, OWNER, OTHER)) {
            db.update("DELETE tp FROM trip_pois tp JOIN trip_days d ON d.id = tp.trip_day_id "
                    + "JOIN trips t ON t.id = d.trip_id WHERE t.user_id = ?", userId);
            db.update("DELETE d FROM trip_days d JOIN trips t ON t.id = d.trip_id WHERE t.user_id = ?", userId);
            db.update("DELETE FROM trip_generation_requests WHERE user_id = ?", userId);
            db.update("DELETE FROM travel_posts WHERE trip_id IN "
                    + "(SELECT id FROM trips WHERE user_id = ?)", userId);
            db.update("DELETE FROM trips WHERE user_id = ?", userId);
            db.update("DELETE FROM wishlist WHERE user_id = ?", userId);
            db.update("DELETE FROM pwd_reset_tokens WHERE user_id = ?", userId);
            db.update("DELETE FROM auth_refresh_tokens WHERE session_id IN "
                    + "(SELECT id FROM auth_sessions WHERE user_id = ?)", userId);
            db.update("DELETE FROM auth_sessions WHERE user_id = ?", userId);
            db.update("DELETE FROM users WHERE id = ?", userId);
        }
        for (String key : List.of("register:127.0.0.1", "login-ip:127.0.0.1")) {
            db.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE ?", secrets.rateHash(key) + ":%");
        }
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
    void cancellingAFinishedTripIsRejected() throws Exception {
        for (TripStatus status : List.of(TripStatus.COMPLETED, TripStatus.CANCELLED)) {
            Long id = trip(OWNER, status);

            assertError(call("/trips/" + id + "/cancel", ownerToken), 409, "INVALID_TRIP_STATUS");
            assertEquals(status.value(), statusOf(id));
        }
    }

    @Test
    void deleteRemovesPlanningTripWithItsDaysPoisAndGenerationRecord() throws Exception {
        Trip trip = tripWithPois(OWNER, TripStatus.PLANNING, TripPoiStatus.PLANNED, TripPoiStatus.PLANNED);
        Long userId = trip.getUser().getId();
        String requestId = UUID.randomUUID().toString();
        db.update("INSERT INTO trip_generation_requests (user_id, request_id, request_hash, trip_id, created_at) "
                + "VALUES (?, ?, ?, ?, NOW(6))", userId, requestId, "0".repeat(64), trip.getId());
        String cacheKey = "trip:generate:" + userId + ":" + requestId;
        redis.opsForValue().set(cacheKey, "{\"status\":\"COMPLETED\"}");

        try {
            MockHttpServletResponse r = deleteTrip(trip.getId(), ownerToken);

            assertEquals(200, r.getStatus(), r.getContentAsString());
            JsonNode body = json.readTree(r.getContentAsString());
            assertEquals("success", body.get("message").asText());
            assertTrue(body.get("data").isNull());
            assertEquals(0, rowsFor(trip.getId()));
            assertFalse(redis.hasKey(cacheKey));
            assertError(detail(trip.getId(), ownerToken), 404, "RESOURCE_NOT_FOUND");
        } finally {
            redis.delete(cacheKey);
        }
    }

    @Test
    void deleteWorksForAPlanningTripWithNoDaysOrGenerationRecord() throws Exception {
        Long id = trip(OWNER, TripStatus.PLANNING);

        assertEquals(200, deleteTrip(id, ownerToken).getStatus());
        assertEquals(0, rowsFor(id));
    }

    @Test
    void deleteRejectsTripsThatAreNoLongerPlanning() throws Exception {
        for (TripStatus status : List.of(TripStatus.ONGOING, TripStatus.COMPLETED, TripStatus.CANCELLED)) {
            Trip trip = tripWithPois(OWNER, status, TripPoiStatus.VISITED, TripPoiStatus.PLANNED);

            assertError(deleteTrip(trip.getId(), ownerToken), 409, "INVALID_TRIP_STATUS");
            assertEquals(status.value(), statusOf(trip.getId()));
            assertEquals(6, rowsFor(trip.getId()));
        }
    }

    @Test
    void deleteRejectsOtherUsersMissingTripsAndAnonymousRequests() throws Exception {
        Trip trip = tripWithPois(OWNER, TripStatus.PLANNING, TripPoiStatus.PLANNED, TripPoiStatus.PLANNED);
        String otherToken = registerAndLogin(OTHER);

        assertError(deleteTrip(trip.getId(), otherToken), 403, "FORBIDDEN");
        assertError(deleteTrip(999999L, ownerToken), 404, "RESOURCE_NOT_FOUND");
        assertError(deleteTrip(trip.getId(), null), 401, "AUTH_REQUIRED");
        assertEquals(6, rowsFor(trip.getId()));
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
        Long ownerId = userRepository.findByEmail(OWNER).orElseThrow().getId();
        assertEquals(0L, db.queryForObject("SELECT COUNT(*) FROM trips WHERE user_id = ?", Long.class, ownerId));
    }

    @Test
    void manualPlanPersistsDestinationOrderMovesAndDeletion() throws Exception {
        stubPlaces();
        var created = manual(null, ownerToken, List.of(List.of("place-a", "place-b"), List.of("place-c")));
        assertEquals(201, created.getStatus(), created.getContentAsString());
        long id = json.readTree(created.getContentAsString()).path("data").path("tripId").asLong();
        var original = json.readTree(detail(id, ownerToken).getContentAsString()).path("data");
        long stableId = original.path("tripDays").get(0).path("tripPois").get(1).path("tripPoiId").asLong();
        assertEquals("New York", original.path("destination").asText());
        assertEquals("2026-12-01", original.path("startDate").asText());
        var updated = manual(id, ownerToken, List.of(List.of("place-c", "place-b"), List.of("place-a")));
        assertEquals(200, updated.getStatus(), updated.getContentAsString());
        var reloaded = json.readTree(detail(id, ownerToken).getContentAsString()).path("data");
        var first = reloaded.path("tripDays").get(0).path("tripPois");
        assertEquals("place-c", first.get(0).path("googlePlaceId").asText());
        assertEquals(stableId, first.get(1).path("tripPoiId").asLong());
        assertEquals(200, manual(id, ownerToken, List.of(List.of("place-b"))).getStatus());
        assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM trip_days WHERE trip_id = ?", Integer.class, id));
        assertEquals(1, json.readTree(detail(id, ownerToken).getContentAsString())
                .path("data").path("tripDays").get(0).path("tripPois").size());
    }

    @Test
    void manualPlanAcceptsFifteenDaysRejectsSixteenAndDuplicates() throws Exception {
        var fifteen = java.util.stream.IntStream.range(0, 15).mapToObj(i -> List.<String>of()).toList();
        assertEquals(201, manual(null, ownerToken, fifteen).getStatus());
        var sixteen = java.util.stream.IntStream.range(0, 16).mapToObj(i -> List.<String>of()).toList();
        assertError(manual(null, ownerToken, sixteen), 400, "INVALID_REQUEST");
        assertError(manual(null, ownerToken, List.of()), 400, "INVALID_REQUEST");
        assertError(manual(null, ownerToken, List.of(List.of("place-a"), List.of("place-a"))), 400, "INVALID_REQUEST");
    }

    @Test
    void manualPlanEnforcesOwnershipAuthenticationAndPlanningStatus() throws Exception {
        Long id = trip(OWNER, TripStatus.PLANNING);
        String other = registerAndLogin(OTHER);
        assertError(manual(id, other, List.of(List.of())), 403, "FORBIDDEN");
        assertError(manual(id, null, List.of(List.of())), 401, "AUTH_REQUIRED");
        assertError(manual(999999L, ownerToken, List.of(List.of())), 404, "RESOURCE_NOT_FOUND");
        Long ongoing = trip(OWNER, TripStatus.ONGOING);
        assertError(manual(ongoing, ownerToken, List.of(List.of())), 409, "INVALID_TRIP_STATUS");
        assertEquals("Hello New York", tripRepository.findById(id).orElseThrow().getName());
    }

    @Test
    void failedPlaceValidationLeavesSavedPlanIntact() throws Exception {
        stubPlaces();
        var created = manual(null, ownerToken, List.of(List.of("place-a")));
        long id = json.readTree(created.getContentAsString()).path("data").path("tripId").asLong();
        when(google.details("invalid-place")).thenThrow(com.travel.common.ApiError.invalid("googlePlaceId", "Unavailable"));
        assertError(manual(id, ownerToken, List.of(List.of("place-b", "invalid-place"))), 400, "INVALID_REQUEST");
        var data = json.readTree(detail(id, ownerToken).getContentAsString()).path("data");
        assertEquals("place-a", data.path("tripDays").get(0).path("tripPois").get(0).path("googlePlaceId").asText());
    }

    MockHttpServletResponse manual(Long id, String token, List<List<String>> days) throws Exception {
        var request = id == null ? post("/trips/manual") : MockMvcRequestBuilders.put("/trips/" + id + "/plan");
        request = withCsrf(request).contentType("application/json").content(json.writeValueAsString(
                Map.of("name", "Manual plan", "destination", "New York", "startDate", "2026-12-01", "days", days)));
        if (token != null) request.header("Authorization", "Bearer " + token);
        return mvc.perform(request).andReturn().getResponse();
    }

    @Test
    void invalidTripIdentifiersReturnClientErrorsRatherThanInternalErrors() throws Exception {
        for (String id : List.of("not-a-number", "-1", "0", "9999999999999999999999999")) {
            var response = mvc.perform(get("/trips/" + id)
                    .header("Authorization", "Bearer " + ownerToken)).andReturn().getResponse();
            assertError(response, 400, "INVALID_REQUEST");
        }
    }

    @Test
    void invalidCalendarDateAndNullDaysAreRejectedBeforePersistence() throws Exception {
        for (String days : List.of("null", "[null]", "[]")) {
            var response = mvc.perform(withCsrf(post("/trips/manual"))
                    .header("Authorization", "Bearer " + ownerToken).contentType("application/json")
                    .content("{\"name\":\"Invalid\",\"destination\":\"Philadelphia\",\"startDate\":\"2026-12-01\",\"days\":" + days + "}"))
                    .andReturn().getResponse();
            assertError(response, 400, "INVALID_REQUEST");
        }
        var response = mvc.perform(withCsrf(post("/trips/manual"))
                .header("Authorization", "Bearer " + ownerToken).contentType("application/json")
                .content("{\"name\":\"Invalid\",\"destination\":\"Philadelphia\",\"startDate\":\"2026-02-30\",\"days\":[[]]}"))
                .andReturn().getResponse();
        assertError(response, 400, "INVALID_REQUEST");
    }

    @Test
    void manualSaveAndStartSerializeOnTheParentLock() throws Exception {
        Long id = trip(OWNER, TripStatus.PLANNING);
        var identity = new TokenService.Identity(userRepository.findByEmail(OWNER).orElseThrow().getId(), UUID.randomUUID());
        var validating = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        stubPlaces();
        var place = google.details("place-a");
        when(google.details("place-a")).thenAnswer(invocation -> {
            validating.countDown();
            if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Validation wait timed out");
            return place;
        });
        var plan = new com.travel.itinerary.vo.SaveTripRequest("Serialized save", "New York",
                LocalDate.of(2026, 12, 1), List.of(List.of("place-a")));
        var pool = Executors.newFixedThreadPool(2);
        try {
            var save = pool.submit(() -> manualTrips.save(identity, id, plan));
            assertTrue(validating.await(10, TimeUnit.SECONDS));
            var start = pool.submit(() -> tripService.start(identity, id));
            org.junit.jupiter.api.Assertions.assertThrows(java.util.concurrent.TimeoutException.class,
                    () -> start.get(150, TimeUnit.MILLISECONDS));
            release.countDown();
            assertEquals(id, save.get(10, TimeUnit.SECONDS));
            start.get(10, TimeUnit.SECONDS);
            assertEquals("Ongoing", statusOf(id));
            assertEquals("Serialized save", tripRepository.findById(id).orElseThrow().getName());
            org.junit.jupiter.api.Assertions.assertThrows(com.travel.common.ApiError.class,
                    () -> manualTrips.save(identity, id, plan));
        } finally {
            release.countDown(); pool.shutdownNow();
        }
    }

    @Test
    void manualSaveRejectsMissingCsrfAndAllNonPlanningStates() throws Exception {
        Long planning = trip(OWNER, TripStatus.PLANNING);
        var response = mvc.perform(MockMvcRequestBuilders.put("/trips/" + planning + "/plan")
                .header("Authorization", "Bearer " + ownerToken).contentType("application/json")
                .content("{\"name\":\"Plan\",\"destination\":\"New York\",\"startDate\":\"2026-12-01\",\"days\":[[]]}"))
                .andReturn().getResponse();
        assertError(response, 403, "CSRF_REJECTED");
        for (TripStatus state : List.of(TripStatus.ONGOING, TripStatus.COMPLETED, TripStatus.CANCELLED)) {
            Long id = trip(OWNER, state);
            assertError(manual(id, ownerToken, List.of(List.of())), 409, "INVALID_TRIP_STATUS");
            assertEquals(state.value(), statusOf(id));
        }
    }

    @Test
    void repositoryFailureRollsBackAlreadyFlushedMetadata() throws Exception {
        stubPlaces();
        var created = manual(null, ownerToken, List.of(List.of("place-a")));
        assertEquals(201, created.getStatus());
        Long id = json.readTree(created.getContentAsString()).path("data").path("tripId").asLong();
        var identity = new TokenService.Identity(userRepository.findByEmail(OWNER).orElseThrow().getId(), UUID.randomUUID());
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataIntegrityViolationException("Synthetic repository failure"))
                .when(tripPois).save(org.mockito.ArgumentMatchers.argThat((TripPoi poi) -> "place-b".equals(poi.getGooglePlaceId())));
        var updated = new com.travel.itinerary.vo.SaveTripRequest("Must roll back", "Philadelphia",
                LocalDate.of(2027, 1, 1), List.of(List.of("place-b")));
        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> manualTrips.save(identity, id, updated));
        var reloaded = json.readTree(detail(id, ownerToken).getContentAsString()).path("data");
        assertEquals("Manual plan", reloaded.path("name").asText());
        assertEquals("New York", reloaded.path("destination").asText());
        assertEquals("2026-12-01", reloaded.path("startDate").asText());
        assertEquals("place-a", reloaded.path("tripDays").get(0).path("tripPois").get(0).path("googlePlaceId").asText());
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

    /** Trip, day, POI and generation-request rows that belong to one trip (tripWithPois makes 6). */
    int rowsFor(Long tripId) {
        return db.queryForObject("SELECT (SELECT COUNT(*) FROM trips WHERE id = ?)"
                + " + (SELECT COUNT(*) FROM trip_days WHERE trip_id = ?)"
                + " + (SELECT COUNT(*) FROM trip_pois tp JOIN trip_days d ON d.id = tp.trip_day_id"
                + " WHERE d.trip_id = ?)"
                + " + (SELECT COUNT(*) FROM trip_generation_requests WHERE trip_id = ?)",
                Integer.class, tripId, tripId, tripId, tripId);
    }

    MockHttpServletResponse deleteTrip(Long tripId, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(MockMvcRequestBuilders.delete("/trips/" + tripId));
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn().getResponse();
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
