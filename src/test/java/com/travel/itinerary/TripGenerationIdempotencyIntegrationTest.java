package com.travel.itinerary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.travel.auth.entity.User;
import com.travel.auth.config.Secrets;
import com.travel.auth.repository.UserRepository;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.itinerary.service.TripService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.service.PoiSearchService;
import com.travel.poisearch.vo.PoiVO;
import java.time.Duration;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** Exercises the idempotency boundary against real MySQL and Redis, without external Google calls. */
@SpringBootTest(properties = {
        "itinerary.generation.rate-limit=5",
        "itinerary.generation.rate-window-seconds=60"
})
class TripGenerationIdempotencyIntegrationTest {
    @Autowired TripService service;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate db;
    @Autowired Secrets secrets;
    @Autowired StringRedisTemplate redis;
    @MockitoBean GooglePlacesClient google;
    @MockitoBean PoiSearchService search;

    private final List<String> redisKeys = new ArrayList<>();
    private Long userId;

    @BeforeEach
    void createUser() {
        User user = new User();
        user.setEmail("trip-idempotency-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("unused-in-service-test");
        userId = users.save(user).getId();
    }

    @AfterEach
    void cleanOwnRecords() {
        if (userId == null) return;
        String database = db.queryForObject("SELECT DATABASE()", String.class);
        if (!"travel_planner_test".equals(database))
            throw new IllegalStateException("Refusing to clean a non-test database");

        if (!redisKeys.isEmpty()) redis.delete(redisKeys);
        db.update("DELETE tp FROM trip_pois tp JOIN trip_days d ON d.id = tp.trip_day_id "
                + "JOIN trips t ON t.id = d.trip_id WHERE t.user_id = ?", userId);
        db.update("DELETE d FROM trip_days d JOIN trips t ON t.id = d.trip_id WHERE t.user_id = ?", userId);
        db.update("DELETE FROM trip_generation_requests WHERE user_id = ?", userId);
        db.update("DELETE FROM trips WHERE user_id = ?", userId);
        db.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE ?",
                secrets.rateHash("trip-generate:" + userId) + ":%");
        db.update("DELETE FROM users WHERE id = ?", userId);
    }

    @Test
    void firstRequestCreatesOneTripAndCompletedCache() {
        PoiVO place = place();
        when(search.search(any())).thenReturn(List.of(place));
        String requestId = requestId();

        TripService.GenerationOutcome outcome = service.generate(identity(), requestId, request("City trip"));

        assertFalse(outcome.processing());
        GeneratedTrip trip = outcome.trip();
        assertNotNull(trip);
        assertNotNull(trip.tripId());
        assertEquals("Planning", trip.status());
        assertEquals(place.googlePlaceId(), trip.tripDays().get(0).tripPois().get(0).googlePlaceId());
        assertEquals(1L, tripCount());
        assertEquals(1L, requestCount());
        assertTrue(redis.opsForValue().get(key(requestId)).contains("COMPLETED"));
    }

    @Test
    void sameRequestReplaysCachedTripWithoutGoogleOrSecondInsert() {
        PoiVO place = place();
        when(search.search(any())).thenReturn(List.of(place));
        String requestId = requestId();
        GenerateTripRequest body = request("City trip");
        TripService.GenerationOutcome first = service.generate(identity(), requestId, body);
        clearInvocations(google, search);

        TripService.GenerationOutcome replay = service.generate(identity(), requestId, body);

        assertFalse(replay.processing());
        assertEquals(first.trip(), replay.trip());
        assertEquals(1L, tripCount());
        assertEquals(1L, requestCount());
        verifyNoInteractions(google, search);
    }

    @Test
    void reusedRequestIdWithDifferentBodyIsConflict() {
        when(search.search(any())).thenReturn(List.of(place()));
        String requestId = requestId();
        service.generate(identity(), requestId, request("Original name"));
        clearInvocations(google, search);

        ApiError error = assertThrows(ApiError.class,
                () -> service.generate(identity(), requestId, request("Different name")));

        assertEquals(HttpStatus.CONFLICT, error.status);
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.code);
        assertEquals(1L, tripCount());
        verifyNoInteractions(google, search);
    }

    @Test
    void missingOrMalformedRequestIdIsRejectedBeforeRedisOrGoogle() {
        GenerateTripRequest body = request("City trip");

        ApiError missing = assertThrows(ApiError.class,
                () -> service.generate(identity(), null, body));
        ApiError malformed = assertThrows(ApiError.class,
                () -> service.generate(identity(), "not-a-uuid", body));

        assertEquals(HttpStatus.BAD_REQUEST, missing.status);
        assertEquals(HttpStatus.BAD_REQUEST, malformed.status);
        assertEquals("INVALID_REQUEST", missing.code);
        assertEquals("INVALID_REQUEST", malformed.code);
        assertEquals(0L, tripCount());
        assertEquals(0L, requestCount());
        assertTrue(redis.keys("trip:generate:" + userId + ":*").isEmpty());
        verifyNoInteractions(google, search);
    }

    @Test
    void mysqlRecordRejectsChangedBodyAfterRedisCacheWasDeleted() {
        when(search.search(any())).thenReturn(List.of(place()));
        String requestId = requestId();
        service.generate(identity(), requestId, request("Original name"));
        redis.delete(key(requestId));
        clearInvocations(google, search);

        ApiError error = assertThrows(ApiError.class,
                () -> service.generate(identity(), requestId, request("Different name")));

        assertEquals(HttpStatus.CONFLICT, error.status);
        assertEquals("IDEMPOTENCY_KEY_REUSED", error.code);
        assertEquals(1L, tripCount());
        assertEquals(1L, requestCount());
        verifyNoInteractions(google, search);
    }

    @Test
    void processingRequestReturnsInFlightWithoutStartingGeneration() {
        when(search.search(any())).thenReturn(List.of(place()));
        String completedId = requestId();
        GenerateTripRequest body = request("City trip");
        service.generate(identity(), completedId, body);
        String hash = db.queryForObject(
                "SELECT request_hash FROM trip_generation_requests WHERE user_id = ? AND request_id = ?",
                String.class, userId, completedId);
        assertNotNull(hash);
        String processingId = requestId();
        redis.opsForValue().set(key(processingId),
                "{\"status\":\"PROCESSING\",\"requestHash\":\"" + hash + "\"}",
                Duration.ofMinutes(2));
        clearInvocations(google, search);

        TripService.GenerationOutcome outcome = service.generate(identity(), processingId, body);

        assertTrue(outcome.processing());
        assertEquals(1L, tripCount());
        assertEquals(1L, requestCount());
        verifyNoInteractions(google, search);
    }

    @Test
    void committedTripIsRecoveredWhenRedisStillSaysProcessing() {
        PoiVO place = place();
        when(search.search(any())).thenReturn(List.of(place));
        String requestId = requestId();
        GenerateTripRequest body = request("City trip");
        TripService.GenerationOutcome first = service.generate(identity(), requestId, body);
        String hash = db.queryForObject(
                "SELECT request_hash FROM trip_generation_requests WHERE user_id = ? AND request_id = ?",
                String.class, userId, requestId);
        assertNotNull(hash);
        redis.opsForValue().set(key(requestId),
                "{\"status\":\"PROCESSING\",\"requestHash\":\"" + hash + "\"}",
                Duration.ofMinutes(2));
        clearInvocations(google, search);
        when(google.details(place.googlePlaceId())).thenReturn(place);

        TripService.GenerationOutcome recovered = service.generate(identity(), requestId, body);

        assertFalse(recovered.processing());
        assertEquals(first.trip(), recovered.trip());
        assertEquals(1L, tripCount());
        assertEquals(1L, requestCount());
        assertTrue(redis.opsForValue().get(key(requestId)).contains("COMPLETED"));
        verify(google).details(place.googlePlaceId());
        verifyNoInteractions(search);
    }

    @Test
    void simultaneousRetrySeesProcessingWhileOriginalIsGenerating() throws Exception {
        PoiVO place = place();
        CountDownLatch searchStarted = new CountDownLatch(1);
        CountDownLatch finishSearch = new CountDownLatch(1);
        when(search.search(any())).thenAnswer(invocation -> {
            searchStarted.countDown();
            if (!finishSearch.await(10, TimeUnit.SECONDS))
                throw new IllegalStateException("Timed out waiting to finish mocked Google search");
            return List.of(place);
        });
        String requestId = requestId();
        GenerateTripRequest body = request("City trip");
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<TripService.GenerationOutcome> first = worker.submit(
                () -> service.generate(identity(), requestId, body));
        try {
            assertTrue(searchStarted.await(10, TimeUnit.SECONDS));
            TripService.GenerationOutcome retry = service.generate(identity(), requestId, body);
            assertTrue(retry.processing());
            assertEquals(0L, tripCount());
        } finally {
            finishSearch.countDown();
            worker.shutdown();
        }

        assertNotNull(first.get(10, TimeUnit.SECONDS).trip());
        assertEquals(1L, tripCount());
        assertEquals(1L, requestCount());
        verify(search, times(1)).search(any());
    }

    @Test
    void expiredProcessingClaimStillAllowsOnlyOneCommittedTrip() throws Exception {
        PoiVO place = place();
        when(google.details(place.googlePlaceId())).thenReturn(place);
        AtomicInteger searches = new AtomicInteger();
        CountDownLatch firstSearching = new CountDownLatch(1);
        CountDownLatch secondSearching = new CountDownLatch(1);
        CountDownLatch finishFirst = new CountDownLatch(1);
        CountDownLatch finishSecond = new CountDownLatch(1);
        when(search.search(any())).thenAnswer(invocation -> {
            int call = searches.incrementAndGet();
            if (call == 1) {
                firstSearching.countDown();
                if (!finishFirst.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("First mocked search timed out");
            } else if (call == 2) {
                secondSearching.countDown();
                if (!finishSecond.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Second mocked search timed out");
            } else {
                throw new IllegalStateException("Unexpected extra Google search");
            }
            return List.of(place);
        });
        String requestId = requestId();
        GenerateTripRequest body = request("City trip");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<TripService.GenerationOutcome> first = workers.submit(
                    () -> service.generate(identity(), requestId, body));
            assertTrue(firstSearching.await(10, TimeUnit.SECONDS));
            // Simulate expiry/loss of the first PROCESSING lease before its MySQL transaction commits.
            redis.delete(key(requestId));
            Future<TripService.GenerationOutcome> second = workers.submit(
                    () -> service.generate(identity(), requestId, body));
            assertTrue(secondSearching.await(10, TimeUnit.SECONDS));

            finishFirst.countDown();
            TripService.GenerationOutcome winner = first.get(10, TimeUnit.SECONDS);
            finishSecond.countDown();
            TripService.GenerationOutcome resolved = second.get(10, TimeUnit.SECONDS);

            assertFalse(winner.processing());
            assertFalse(resolved.processing());
            assertEquals(winner.trip().tripId(), resolved.trip().tripId());
            assertEquals(1L, tripCount());
            assertEquals(1L, requestCount());
            assertTrue(redis.opsForValue().get(key(requestId)).contains("COMPLETED"));
            verify(search, times(2)).search(any());
        } finally {
            finishFirst.countDown();
            finishSecond.countDown();
            workers.shutdown();
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        }
    }

    @Test
    void missingRedisCacheRecoversCommittedTripAndPlaceDetailsFromMysql() {
        PoiVO place = place();
        when(search.search(any())).thenReturn(List.of(place));
        String requestId = requestId();
        GenerateTripRequest body = request("City trip");
        TripService.GenerationOutcome first = service.generate(identity(), requestId, body);
        redis.delete(key(requestId));
        clearInvocations(google, search);
        when(google.details(place.googlePlaceId())).thenReturn(place);

        TripService.GenerationOutcome recovered = service.generate(identity(), requestId, body);

        assertFalse(recovered.processing());
        assertEquals(first.trip(), recovered.trip());
        assertEquals(1L, tripCount());
        assertEquals(1L, requestCount());
        assertTrue(redis.opsForValue().get(key(requestId)).contains("COMPLETED"));
        verify(google).details(place.googlePlaceId());
        verifyNoInteractions(search);
    }

    @Test
    void generationFailureBeforeCommitReleasesClaimAndAllowsSameKeyRetry() {
        when(search.search(any())).thenThrow(new IllegalStateException("synthetic Google failure"));
        String requestId = requestId();
        GenerateTripRequest body = request("Failing trip");

        assertThrows(RuntimeException.class,
                () -> service.generate(identity(), requestId, body));

        assertEquals(0L, tripCount());
        assertEquals(0L, requestCount());
        assertEquals(Boolean.FALSE, redis.hasKey(key(requestId)));

        reset(search);
        when(search.search(any())).thenReturn(List.of(place()));
        TripService.GenerationOutcome retry = service.generate(identity(), requestId, body);
        assertFalse(retry.processing());
        assertEquals(1L, tripCount());
        assertEquals(1L, requestCount());
    }

    @Test
    void failedOldClaimCannotDeleteNewProcessingClaim() throws Exception {
        PoiVO place = place();
        AtomicInteger searches = new AtomicInteger();
        CountDownLatch firstSearching = new CountDownLatch(1);
        CountDownLatch secondSearching = new CountDownLatch(1);
        CountDownLatch failFirst = new CountDownLatch(1);
        CountDownLatch finishSecond = new CountDownLatch(1);
        when(search.search(any())).thenAnswer(invocation -> {
            int call = searches.incrementAndGet();
            if (call == 1) {
                firstSearching.countDown();
                if (!failFirst.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("First mocked search timed out");
                throw new IllegalStateException("First mocked search failed");
            }
            if (call == 2) {
                secondSearching.countDown();
                if (!finishSecond.await(10, TimeUnit.SECONDS))
                    throw new IllegalStateException("Second mocked search timed out");
                return List.of(place);
            }
            throw new IllegalStateException("Unexpected extra Google search");
        });
        String requestId = requestId();
        GenerateTripRequest body = request("City trip");
        ExecutorService workers = Executors.newFixedThreadPool(2);
        try {
            Future<TripService.GenerationOutcome> first = workers.submit(
                    () -> service.generate(identity(), requestId, body));
            assertTrue(firstSearching.await(10, TimeUnit.SECONDS));
            String oldClaim = redis.opsForValue().get(key(requestId));
            assertNotNull(oldClaim);
            // Simulate expiry of A's claim before B acquires the same key.
            redis.delete(key(requestId));
            Future<TripService.GenerationOutcome> second = workers.submit(
                    () -> service.generate(identity(), requestId, body));
            assertTrue(secondSearching.await(10, TimeUnit.SECONDS));
            String newClaim = redis.opsForValue().get(key(requestId));
            assertNotNull(newClaim);
            assertNotEquals(oldClaim, newClaim);

            failFirst.countDown();
            assertThrows(ExecutionException.class, () -> first.get(10, TimeUnit.SECONDS));
            assertEquals(newClaim, redis.opsForValue().get(key(requestId)));
            assertTrue(service.generate(identity(), requestId, body).processing());
            assertEquals(2, searches.get());

            finishSecond.countDown();
            assertNotNull(second.get(10, TimeUnit.SECONDS).trip());
            assertEquals(1L, tripCount());
        } finally {
            failFirst.countDown();
            finishSecond.countDown();
            workers.shutdown();
            if (!workers.awaitTermination(10, TimeUnit.SECONDS)) workers.shutdownNow();
        }
    }

    @Test
    void sixthDistinctRequestIsRateLimitedBeforeGoogleOrRedisClaim() {
        when(search.search(any())).thenReturn(List.of(place()));
        GenerateTripRequest body = request("City trip");
        for (int i = 0; i < 5; i++) {
            TripService.GenerationOutcome outcome = service.generate(identity(), requestId(), body);
            assertFalse(outcome.processing());
        }
        String rejectedId = requestId();

        ApiError error = assertThrows(ApiError.class,
                () -> service.generate(identity(), rejectedId, body));

        assertEquals(HttpStatus.TOO_MANY_REQUESTS, error.status);
        assertEquals("RATE_LIMITED", error.code);
        assertEquals(5L, tripCount());
        assertEquals(5L, requestCount());
        assertEquals(Boolean.FALSE, redis.hasKey(key(rejectedId)));
        verify(search, times(5)).search(any());
        verifyNoInteractions(google);
    }

    private TokenService.Identity identity() {
        return new TokenService.Identity(userId, UUID.randomUUID());
    }

    private String requestId() {
        String id = UUID.randomUUID().toString();
        redisKeys.add(key(id));
        return id;
    }

    private String key(String requestId) {
        return "trip:generate:" + userId + ":" + requestId;
    }

    private long tripCount() {
        return db.queryForObject("SELECT COUNT(*) FROM trips WHERE user_id = ?", Long.class, userId);
    }

    private long requestCount() {
        return db.queryForObject("SELECT COUNT(*) FROM trip_generation_requests WHERE user_id = ?",
                Long.class, userId);
    }

    private static GenerateTripRequest request(String name) {
        return new GenerateTripRequest(name, LocalDate.of(2026, 12, 1), 1,
                new GenerateTripRequest.SearchArea(40.758, -73.9855, 15_000),
                new GenerateTripRequest.Constraints(2, List.of()), null);
    }

    private static PoiVO place() {
        return new PoiVO("trip-idempotency-place-" + UUID.randomUUID(), "Museum A", "New York, NY",
                40.758, -73.9855, "museum", 4.7, "museum", List.of("museum"), 1200L, "OPERATIONAL");
    }
}
