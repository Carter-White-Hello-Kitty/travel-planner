package com.travel.itinerary.service.impl;

import com.travel.auth.entity.User;
import com.travel.auth.repository.UserRepository;
import com.travel.auth.security.Ownership;
import com.travel.auth.service.TokenService;
import com.travel.auth.support.RateLimiter;
import com.travel.common.ApiError;
import com.travel.common.Result;
import com.travel.itinerary.constant.RedisConstant;
import com.travel.itinerary.entity.Trip;
import com.travel.itinerary.entity.TripDay;
import com.travel.itinerary.entity.TripGenerationRequest;
import com.travel.itinerary.entity.TripPoi;
import com.travel.itinerary.entity.TripPoiStatus;
import com.travel.itinerary.entity.TripStatus;
import com.travel.itinerary.repository.TripDayRepository;
import com.travel.itinerary.repository.TripGenerationRequestRepository;
import com.travel.itinerary.repository.TripPoiRepository;
import com.travel.itinerary.repository.TripRepository;
import com.travel.itinerary.service.TripGenerationService;
import com.travel.itinerary.service.TripService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import com.travel.itinerary.vo.TripSummary;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.vo.PoiVO;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.validation.annotation.Validated;
import tools.jackson.databind.json.JsonMapper;

@Service
@Validated
public class TripServiceImpl implements TripService {
    private static final String PROCESSING = "PROCESSING";
    private static final String COMPLETED = "COMPLETED";

    private final TripRepository tripRepository;
    private final TripDayRepository tripDayRepository;
    private final TripPoiRepository tripPoiRepository;
    private final TripGenerationRequestRepository generationRequests;
    private final UserRepository userRepository;
    private final GooglePlacesClient google;
    private final TripGenerationService generationService;
    private final TransactionTemplate transactions;
    private final StringRedisTemplate redis;
    private final RateLimiter rateLimiter;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final Duration processingTtl;
    private final Duration completedTtl;
    private final Duration maxGenerationDuration;
    private final int rateLimit;
    private final int rateWindowSeconds;

    public TripServiceImpl(TripRepository tripRepository, TripDayRepository tripDayRepository,
            TripPoiRepository tripPoiRepository, TripGenerationRequestRepository generationRequests,
            UserRepository userRepository, GooglePlacesClient google, TripGenerationService generationService,
            PlatformTransactionManager transactionManager, StringRedisTemplate redis,
            RateLimiter rateLimiter, Clock clock,
            @Value("${itinerary.generation.processing-ttl:15m}") Duration processingTtl,
            @Value("${itinerary.generation.completed-ttl:24h}") Duration completedTtl,
            @Value("${itinerary.generation.max-duration:10m}") Duration maxGenerationDuration,
            @Value("${itinerary.generation.rate-limit:5}") int rateLimit,
            @Value("${itinerary.generation.rate-window-seconds:60}") int rateWindowSeconds) {
        this.tripRepository = tripRepository;
        this.tripDayRepository = tripDayRepository;
        this.tripPoiRepository = tripPoiRepository;
        this.generationRequests = generationRequests;
        this.userRepository = userRepository;
        this.google = google;
        this.generationService = generationService;
        this.transactions = new TransactionTemplate(transactionManager);
        this.redis = redis;
        this.rateLimiter = rateLimiter;
        this.clock = clock;
        this.processingTtl = processingTtl;
        this.completedTtl = completedTtl;
        this.maxGenerationDuration = maxGenerationDuration;
        this.rateLimit = rateLimit;
        this.rateWindowSeconds = rateWindowSeconds;
        if (processingTtl.compareTo(maxGenerationDuration) <= 0 || maxGenerationDuration.isNegative()
                || maxGenerationDuration.isZero() || completedTtl.isNegative() || completedTtl.isZero()
                || rateLimit < 1 || rateWindowSeconds < 1)
            throw new IllegalArgumentException("Invalid itinerary generation limits");
    }

    @Override
    public GenerationOutcome generate(TokenService.Identity identity, String requestId,
            GenerateTripRequest request) {
        if (identity == null || identity.userId() == null)
            throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        String canonicalId = canonicalRequestId(requestId);
        String hash = requestHash(request);
        String key = RedisConstant.TRIP_GENERATION_KEY_PREFIX + identity.userId() + ":" + canonicalId;

        GenerationOutcome cached = readCached(key, hash);
        if (cached != null) {
            if (!cached.processing()) return cached;
            // The trip may have committed while Redis was still PROCESSING (for example, a cache write failed).
            GenerationOutcome committedWhileProcessing = restoreCommitted(
                    identity.userId(), canonicalId, hash, key);
            return committedWhileProcessing != null ? committedWhileProcessing : cached;
        }
        GenerationOutcome committed = restoreCommitted(identity.userId(), canonicalId, hash, key);
        if (committed != null) return committed;

        // RateLimiter uses its own short MySQL transaction; never call it inside the trip save transaction.
        rateLimiter.hit("trip-generate:" + identity.userId(), rateLimit, rateWindowSeconds);
        RedisState claim = new RedisState(PROCESSING, hash, null);
        boolean claimed = redisClaim(key, claim);
        if (!claimed) {
            GenerationOutcome concurrent = readCached(key, hash);
            if (concurrent != null) {
                if (!concurrent.processing()) return concurrent;
                GenerationOutcome committedMeanwhile = restoreCommitted(
                        identity.userId(), canonicalId, hash, key);
                return committedMeanwhile != null ? committedMeanwhile : concurrent;
            }
            // The other request's lease may have expired between SET NX and GET.
            committed = restoreCommitted(identity.userId(), canonicalId, hash, key);
            if (committed != null) return committed;
            throw new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "GENERATION_STATE_UNAVAILABLE",
                    "Generation state changed; retry with the same Idempotency-Key");
        }

        List<List<PoiVO>> plan = generationService.generatePlan(request, maxGenerationDuration);
        try {
            GeneratedTrip saved = transactions.execute(status ->
                    savePlan(identity.userId(), canonicalId, hash, request, plan));
            cacheCompleted(key, hash, saved);
            return new GenerationOutcome(saved, false);
        } catch (DataIntegrityViolationException duplicateOrDatabaseError) {
            // The failed transaction has rolled back. Look for the competing committed request now.
            GenerationOutcome winner = restoreCommitted(identity.userId(), canonicalId, hash, key);
            if (winner != null) return winner;
            throw duplicateOrDatabaseError;
        }
    }

    private GeneratedTrip savePlan(Long userId, String requestId, String requestHash,
            GenerateTripRequest request, List<List<PoiVO>> plan) {
        User owner = userRepository.findById(userId).orElseThrow(() ->
                new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required"));
        Trip trip = new Trip();
        trip.setUser(owner);
        trip.setName(request.name().strip());
        trip.setStartDate(request.startDate());
        trip.setDayCount(plan.size());
        trip.setStatus(TripStatus.PLANNING);
        for (int dayIndex = 0; dayIndex < plan.size(); dayIndex++) {
            TripDay day = new TripDay();
            day.setDayNumber(dayIndex + 1);
            trip.addDay(day);

            for (int position = 0; position < plan.get(dayIndex).size(); position++) {
                PoiVO info = plan.get(dayIndex).get(position);
                TripPoi tripPoi = new TripPoi();
                tripPoi.setGooglePlaceId(info.googlePlaceId());
                tripPoi.setSequence(position + 1);
                tripPoi.setStatus(TripPoiStatus.PLANNED);
                day.addPoi(tripPoi);
            }
        }

        tripRepository.save(trip);
        TripGenerationRequest generation = new TripGenerationRequest();
        generation.setUserId(userId);
        generation.setRequestId(requestId);
        generation.setRequestHash(requestHash);
        generation.setTripId(trip.getId());
        generation.setCreatedAt(LocalDateTime.ofInstant(clock.instant(), ZoneOffset.UTC));
        generationRequests.save(generation);
        List<GeneratedTrip.Day> resultDays = new ArrayList<>();
        for (int dayIndex = 0; dayIndex < plan.size(); dayIndex++) {
            List<GeneratedTrip.Poi> resultPois = new ArrayList<>();
            List<TripPoi> savedPois = trip.getTripDays().get(dayIndex).getTripPois();
            for (int position = 0; position < savedPois.size(); position++) {
                TripPoi tripPoi = savedPois.get(position);
                PoiVO info = plan.get(dayIndex).get(position);
                resultPois.add(new GeneratedTrip.Poi(tripPoi.getId(), info.googlePlaceId(),
                        info.name(), info.address(), info.latitude(), info.longitude(),
                        tripPoi.getStatus().value()));
            }
            resultDays.add(new GeneratedTrip.Day(dayIndex + 1, List.copyOf(resultPois)));
        }
        return new GeneratedTrip(trip.getId(), trip.getName(), trip.getStartDate(),
                trip.getStatus().value(), plan.size(), List.copyOf(resultDays));
    }

    private String canonicalRequestId(String requestId) {
        if (requestId == null || requestId.length() != 36)
            throw ApiError.invalid("Idempotency-Key", "A UUID Idempotency-Key is required");
        try {
            UUID parsed = UUID.fromString(requestId);
            if (!parsed.toString().equalsIgnoreCase(requestId))
                throw new IllegalArgumentException("Non-canonical UUID");
            return parsed.toString();
        } catch (IllegalArgumentException e) {
            throw ApiError.invalid("Idempotency-Key", "A UUID Idempotency-Key is required");
        }
    }

    private String requestHash(GenerateTripRequest request) {
        List<CanonicalDay> days = request.tripDays() == null || request.searchArea() != null
                ? List.of() : request.tripDays().stream()
                        .sorted(Comparator.comparing(GenerateTripRequest.Day::dayNumber))
                        .map(day -> new CanonicalDay(day.dayNumber(), day.mustVisitPois().stream()
                                .map(GenerateTripRequest.MustVisitPoi::googlePlaceId).toList()))
                        .toList();
        CanonicalRequest canonical = new CanonicalRequest(request.name().strip(),
                request.startDate().toString(), request.dayCount(), request.searchArea(),
                request.constraints().maxPoisPerDay(),
                request.constraints().preferredTypes() == null
                        ? List.of() : request.constraints().preferredTypes(), days);
        try {
            byte[] bytes = json.writeValueAsBytes(canonical);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private GenerationOutcome readCached(String key, String requestHash) {
        String raw;
        try {
            raw = redis.opsForValue().get(key);
        } catch (org.springframework.dao.DataAccessException e) {
            throw redisUnavailable();
        }
        if (raw == null) return null;
        RedisState state;
        try {
            state = json.readValue(raw, RedisState.class);
        } catch (RuntimeException e) {
            throw redisUnavailable();
        }
        if (state == null || state.requestHash() == null)
            throw redisUnavailable();
        requireSameRequest(state.requestHash(), requestHash);
        if (PROCESSING.equals(state.status())) return new GenerationOutcome(null, true);
        if (COMPLETED.equals(state.status()) && state.response() != null
                && state.response().data() != null)
            return new GenerationOutcome(state.response().data(), false);
        throw redisUnavailable();
    }

    private boolean redisClaim(String key, RedisState state) {
        try {
            Boolean claimed = redis.opsForValue().setIfAbsent(key,
                    json.writeValueAsString(state), processingTtl);
            if (claimed == null) throw redisUnavailable();
            return claimed;
        } catch (org.springframework.dao.DataAccessException e) {
            throw redisUnavailable();
        }
    }

    private void cacheCompleted(String key, String requestHash, GeneratedTrip trip) {
        RedisState state = new RedisState(COMPLETED, requestHash, Result.success(trip));
        try {
            redis.opsForValue().set(key, json.writeValueAsString(state), completedTtl);
        } catch (org.springframework.dao.DataAccessException e) {
            // The MySQL transaction has committed; a retry recovers the same trip from its request row.
            throw redisUnavailable();
        }
    }

    private GenerationOutcome restoreCommitted(Long userId, String requestId,
            String requestHash, String redisKey) {
        TripGenerationRequest recorded = generationRequests.findByUserIdAndRequestId(userId, requestId)
                .orElse(null);
        if (recorded == null) return null;
        requireSameRequest(recorded.getRequestHash(), requestHash);
        SavedTrip saved = transactions.execute(status -> loadSavedTrip(userId, recorded.getTripId()));
        GeneratedTrip restored = restoreDisplayFields(saved);
        cacheCompleted(redisKey, requestHash, restored);
        return new GenerationOutcome(restored, false);
    }

    private SavedTrip loadSavedTrip(Long userId, Long tripId) {
        Trip trip = tripRepository.findByIdAndUserId(tripId, userId).orElseThrow(() ->
                new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "GENERATION_RECORD_INVALID",
                        "Saved trip is unavailable"));
        List<SavedDay> days = new ArrayList<>();
        for (TripDay day : tripDayRepository.findByTripIdOrderByDayNumberAsc(tripId)) {
            List<SavedPoi> pois = tripPoiRepository.findByTripDayIdOrderBySequenceAsc(day.getId())
                    .stream().map(poi -> new SavedPoi(poi.getId(), poi.getGooglePlaceId(),
                            poi.getStatus().value())).toList();
            days.add(new SavedDay(day.getDayNumber(), pois));
        }
        return new SavedTrip(trip.getId(), trip.getName(), trip.getStartDate(),
                trip.getStatus().value(), trip.getDayCount(), List.copyOf(days));
    }

    private GeneratedTrip restoreDisplayFields(SavedTrip saved) {
        List<GeneratedTrip.Day> days = new ArrayList<>();
        for (SavedDay day : saved.days()) {
            List<GeneratedTrip.Poi> pois = new ArrayList<>();
            for (SavedPoi poi : day.pois()) {
                PoiVO details = google.details(poi.googlePlaceId());
                if (details == null || !poi.googlePlaceId().equals(details.googlePlaceId()))
                    throw new ApiError(HttpStatus.BAD_GATEWAY, "POI_SEARCH_UPSTREAM_ERROR",
                            "POI search provider is temporarily unavailable");
                pois.add(new GeneratedTrip.Poi(poi.id(), poi.googlePlaceId(), details.name(),
                        details.address(), details.latitude(), details.longitude(), poi.status()));
            }
            days.add(new GeneratedTrip.Day(day.dayNumber(), List.copyOf(pois)));
        }
        return new GeneratedTrip(saved.id(), saved.name(), saved.startDate(), saved.status(),
                saved.dayCount(), List.copyOf(days));
    }

    private static void requireSameRequest(String storedHash, String requestHash) {
        if (!requestHash.equals(storedHash))
            throw new ApiError(HttpStatus.CONFLICT, "IDEMPOTENCY_KEY_REUSED",
                    "Idempotency-Key was already used for different trip parameters");
    }

    private static ApiError redisUnavailable() {
        return new ApiError(HttpStatus.SERVICE_UNAVAILABLE, "GENERATION_STATE_UNAVAILABLE",
                "Trip generation state is temporarily unavailable");
    }

    private record CanonicalDay(Integer dayNumber, List<String> mustVisitPois) {}
    private record CanonicalRequest(String name, String startDate, Integer dayCount,
            GenerateTripRequest.SearchArea searchArea, Integer maxPoisPerDay,
            List<String> preferredTypes, List<CanonicalDay> tripDays) {}
    private record RedisState(String status, String requestHash,
            @JsonInclude(JsonInclude.Include.NON_NULL) Result<GeneratedTrip> response) {}
    private record SavedPoi(Long id, String googlePlaceId, String status) {}
    private record SavedDay(Integer dayNumber, List<SavedPoi> pois) {}
    private record SavedTrip(Long id, String name, LocalDate startDate,
            String status, Integer dayCount, List<SavedDay> days) {}

    @Override
    @Transactional(readOnly = true)
    public List<TripSummary> list(TokenService.Identity identity) {
        if (identity == null)
            throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Authentication required");
        return tripRepository.findByUserIdOrderByIdDesc(identity.userId()).stream()
                .map(t -> new TripSummary(t.getId(), t.getName(), t.getStatus().value(), t.getDayCount()))
                .toList();
    }

    @Override
    @Transactional
    public void start(TokenService.Identity identity, Long tripId) {
        changeStatus(identity, tripId, TripStatus.ONGOING);
    }

    @Override
    @Transactional
    public void cancel(TokenService.Identity identity, Long tripId) {
        changeStatus(identity, tripId, TripStatus.CANCELLED);
    }

    /** Loads the caller's trip under a row lock and applies a legal status transition. */
    private void changeStatus(TokenService.Identity identity, Long tripId, TripStatus next) {
        Trip trip = tripRepository.findByIdForUpdate(tripId)
                .orElseThrow(() -> new ApiError(HttpStatus.NOT_FOUND, "RESOURCE_NOT_FOUND", "Trip not found"));
        Ownership.requireOwner(identity, trip.getUser().getId());

        TripStatus current = trip.getStatus();
        if (!current.canTransitionTo(next)) {
            throw new ApiError(HttpStatus.CONFLICT, "INVALID_TRIP_STATUS",
                    "Cannot change trip from " + current.value() + " to " + next.value());
        }
        trip.setStatus(next);
    }
}
