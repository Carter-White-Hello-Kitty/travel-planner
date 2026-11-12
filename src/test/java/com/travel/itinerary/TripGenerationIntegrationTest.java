package com.travel.itinerary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.travel.auth.entity.User;
import com.travel.auth.repository.UserRepository;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.itinerary.entity.TripPoi;
import com.travel.itinerary.entity.TripPoiStatus;
import com.travel.itinerary.entity.TripStatus;
import com.travel.itinerary.repository.TripDayRepository;
import com.travel.itinerary.repository.TripPoiRepository;
import com.travel.itinerary.repository.TripRepository;
import com.travel.itinerary.service.TripService;
import com.travel.itinerary.vo.GenerateTripRequest;
import com.travel.itinerary.vo.GeneratedTrip;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.service.PoiSearchService;
import com.travel.poisearch.vo.PoiSearchRequest;
import com.travel.poisearch.vo.PoiVO;
import jakarta.persistence.EntityManager;
import jakarta.validation.ConstraintViolationException;
import java.time.LocalDate;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** Real service and JPA persistence; only Google-dependent calls are mocked. */
@SpringBootTest
@Transactional
class TripGenerationIntegrationTest {
    @Autowired TripService service;
    @Autowired UserRepository users;
    @Autowired TripRepository trips;
    @Autowired TripDayRepository days;
    @Autowired TripPoiRepository tripPois;
    @Autowired EntityManager entityManager;
    @MockitoBean GooglePlacesClient google;
    @MockitoBean PoiSearchService search;
    @MockitoBean StringRedisTemplate redis;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void mockRedis() {
        ValueOperations<String, String> values = org.mockito.Mockito.mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(values);
        when(values.setIfAbsent(any(), any(), any(Duration.class))).thenReturn(true);
    }

    @Test
    void mustVisitModeDerivesAreaAndSavesOrderedPlanningTrip() {
        Long userId = createUser();
        PoiVO a = poi("A", 40.75, -73.99);
        PoiVO b = poi("B", 40.76, -73.98);
        PoiVO c = poi("C", 40.77, -73.97);
        when(google.details(a.googlePlaceId())).thenReturn(a);
        when(google.details(b.googlePlaceId())).thenReturn(b);
        when(search.search(any())).thenReturn(List.of(c));

        GeneratedTrip result = generate(identity(userId), UUID.randomUUID().toString(), new GenerateTripRequest(
                "New York Trip", LocalDate.of(2026, 12, 1), 2, null,
                new GenerateTripRequest.Constraints(2, List.of("museum")),
                List.of(new GenerateTripRequest.Day(1, List.of(
                                new GenerateTripRequest.MustVisitPoi(a.googlePlaceId()),
                                new GenerateTripRequest.MustVisitPoi(b.googlePlaceId()))),
                        new GenerateTripRequest.Day(2, List.of())))).trip();

        assertEquals("Planning", result.status());
        assertEquals(LocalDate.of(2026, 12, 1), result.startDate());
        assertEquals(2, result.tripDays().size());
        assertEquals(List.of(a.googlePlaceId(), b.googlePlaceId()),
                result.tripDays().get(0).tripPois().stream().map(GeneratedTrip.Poi::googlePlaceId).toList());
        assertEquals(c.googlePlaceId(), result.tripDays().get(1).tripPois().get(0).googlePlaceId());
        assertNotNull(result.tripDays().get(0).tripPois().get(0).tripPoiId());

        ArgumentCaptor<PoiSearchRequest> area = ArgumentCaptor.forClass(PoiSearchRequest.class);
        verify(search, org.mockito.Mockito.atLeastOnce()).search(area.capture());
        assertEquals(20_000, area.getValue().radius());
        assertTrue(area.getValue().latitude() > 40.75 && area.getValue().latitude() < 40.76);

        entityManager.flush();
        entityManager.clear();
        var persisted = trips.findById(result.tripId()).orElseThrow();
        assertEquals(userId, persisted.getUser().getId());
        assertEquals(TripStatus.PLANNING, persisted.getStatus());
        assertEquals(result.startDate(), persisted.getStartDate());
        var savedDays = days.findByTripIdOrderByDayNumberAsc(result.tripId());
        assertEquals(List.of(1, 2), savedDays.stream().map(day -> day.getDayNumber()).toList());
        List<TripPoi> firstDayPois = tripPois.findByTripDayIdOrderBySequenceAsc(savedDays.get(0).getId());
        assertEquals(List.of(1, 2), firstDayPois.stream().map(TripPoi::getSequence).toList());
        assertEquals(List.of(a.googlePlaceId(), b.googlePlaceId()),
                firstDayPois.stream().map(TripPoi::getGooglePlaceId).toList());
        assertEquals(TripPoiStatus.PLANNED, firstDayPois.get(0).getStatus());
    }

    @Test
    void cityModeUsesProvidedAreaAndNeedsNoMustVisitDays() {
        Long userId = createUser();
        PoiVO a = poi("A", 40.75, -73.99);
        PoiVO b = poi("B", 40.76, -73.98);
        when(search.search(any())).thenReturn(List.of(a, b));
        var area = new GenerateTripRequest.SearchArea(40.758, -73.9855, 15_000);

        GeneratedTrip result = generate(identity(userId), UUID.randomUUID().toString(), new GenerateTripRequest(
                "City trip", LocalDate.of(2026, 12, 1), 2, area,
                new GenerateTripRequest.Constraints(2, List.of("museum")), null)).trip();

        assertEquals(2, result.tripDays().size());
        assertEquals(1, result.tripDays().get(0).tripPois().size());
        assertEquals(1, result.tripDays().get(1).tripPois().size());
        ArgumentCaptor<PoiSearchRequest> searchArea = ArgumentCaptor.forClass(PoiSearchRequest.class);
        verify(search, org.mockito.Mockito.atLeastOnce()).search(searchArea.capture());
        assertEquals(area.latitude(), searchArea.getValue().latitude());
        assertEquals(area.radiusMeters(), searchArea.getValue().radius());
        verifyNoInteractions(google);
    }

    @Test
    void mustVisitModeExpandsSearchRadiusToCoverSeparatedPlaces() {
        Long userId = createUser();
        PoiVO a = poi("A", 40.0, -73.0);
        PoiVO b = poi("B", 40.36, -73.0);
        when(google.details(a.googlePlaceId())).thenReturn(a);
        when(google.details(b.googlePlaceId())).thenReturn(b);
        when(search.search(any())).thenReturn(List.of());

        generate(identity(userId), UUID.randomUUID().toString(), new GenerateTripRequest("Two places",
                LocalDate.of(2026, 12, 1), 2, null,
                new GenerateTripRequest.Constraints(1, List.of()),
                List.of(new GenerateTripRequest.Day(1, List.of(new GenerateTripRequest.MustVisitPoi(a.googlePlaceId()))),
                        new GenerateTripRequest.Day(2, List.of(new GenerateTripRequest.MustVisitPoi(b.googlePlaceId()))))));

        ArgumentCaptor<PoiSearchRequest> area = ArgumentCaptor.forClass(PoiSearchRequest.class);
        verify(search).search(area.capture());
        assertTrue(area.getValue().radius() > 20_000 && area.getValue().radius() < 50_000);
    }

    @Test
    void nestedAnnotationsRejectInvalidCityRadiusBeforeSearch() {
        Long userId = createUser();
        var request = new GenerateTripRequest("City trip", LocalDate.of(2026, 12, 1), 2,
                new GenerateTripRequest.SearchArea(40.758, -73.9855, 0),
                new GenerateTripRequest.Constraints(2, List.of()), null);

        assertThrows(ConstraintViolationException.class, () -> generate(identity(userId), UUID.randomUUID().toString(), request));
        verifyNoInteractions(google, search);
    }

    @Test
    void annotationsRejectDuplicateMustVisitsBeforeExternalCalls() {
        Long userId = createUser();
        String id = "test-place-" + UUID.randomUUID();
        var request = new GenerateTripRequest("Duplicate", LocalDate.of(2026, 12, 1), 2, null,
                new GenerateTripRequest.Constraints(2, List.of()),
                List.of(new GenerateTripRequest.Day(1, List.of(new GenerateTripRequest.MustVisitPoi(id))),
                        new GenerateTripRequest.Day(2, List.of(new GenerateTripRequest.MustVisitPoi(id)))));

        assertThrows(ConstraintViolationException.class, () -> generate(identity(userId), UUID.randomUUID().toString(), request));
        verifyNoInteractions(google, search);
    }

    @Test
    void rejectsRequiredPlacesTooFarApartBeforeSearchingOrSaving() {
        Long userId = createUser();
        PoiVO a = poi("NY", 40.75, -73.99);
        PoiVO b = poi("LA", 34.05, -118.24);
        when(google.details(a.googlePlaceId())).thenReturn(a);
        when(google.details(b.googlePlaceId())).thenReturn(b);
        var request = new GenerateTripRequest("Wide trip", LocalDate.of(2026, 12, 1), 2, null,
                new GenerateTripRequest.Constraints(2, List.of()),
                List.of(new GenerateTripRequest.Day(1, List.of(new GenerateTripRequest.MustVisitPoi(a.googlePlaceId()))),
                        new GenerateTripRequest.Day(2, List.of(new GenerateTripRequest.MustVisitPoi(b.googlePlaceId())))));

        ApiError error = assertThrows(ApiError.class, () -> generate(identity(userId), UUID.randomUUID().toString(), request));
        assertEquals("INVALID_REQUEST", error.code);
        verifyNoInteractions(search);
        assertEquals(1, trips.findByUserIdOrderByIdDesc(userId).size());
    }

    private Long draftId;

    private TripService.GenerationOutcome generate(TokenService.Identity caller, String id, GenerateTripRequest body) {
        return service.generate(caller, draftId, id, body);
    }

    private static TokenService.Identity identity(Long userId) {
        return new TokenService.Identity(userId, UUID.randomUUID());
    }

    private static PoiVO poi(String suffix, double lat, double lon) {
        String id = "trip-gen-" + UUID.randomUUID() + "-" + suffix;
        return new PoiVO(id, "Place " + suffix, "New York, NY", lat, lon,
                "museum", 4.7, "museum", List.of("museum"), 1200L, "OPERATIONAL");
    }

    private Long createUser() {
        User user = new User();
        user.setEmail("trip-gen-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("unused-in-service-test");
        Long userId = users.save(user).getId();
        draftId = service.save(identity(userId), null, null, true).tripId();
        return userId;
    }
}
