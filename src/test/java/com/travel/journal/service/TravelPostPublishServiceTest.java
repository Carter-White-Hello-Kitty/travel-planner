package com.travel.journal.service;

import com.travel.journal.service.impl.TravelPostPublishServiceImpl;
import com.travel.journal.entity.TravelPost;
import com.travel.itinerary.entity.Trip;
import com.travel.auth.entity.User;
import com.travel.journal.repository.TravelPostRepository;
import com.travel.journal.vo.CreateTravelPostRequest;
import com.travel.itinerary.entity.TripStatus;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import com.travel.common.ApiError;
import tools.jackson.databind.json.JsonMapper;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class TravelPostPublishServiceTest {
    private EntityManager em;
    private TravelPostRepository repository;
    private ApplicationEventPublisher events;
    private TravelPostPublishService service;
    private Trip trip;

    @BeforeEach void setup() {
        em = mock(EntityManager.class);
        repository = mock(TravelPostRepository.class);
        events = mock(ApplicationEventPublisher.class);
        service = new TravelPostPublishServiceImpl(em, repository, JsonMapper.builder().build(), events);
        User owner = new User(); owner.setId(7L);
        trip = new Trip(); trip.setId(12L); trip.setUser(owner); trip.setStatus(TripStatus.COMPLETED.value());
        when(em.find(Trip.class, 12L, LockModeType.PESSIMISTIC_WRITE)).thenReturn(trip);
        when(repository.save(any(TravelPost.class))).thenAnswer(call -> {
            TravelPost post = call.getArgument(0); post.setId(50L); return post;
        });
    }

    @ParameterizedTest @EnumSource(value = TripStatus.class, names = {"COMPLETED", "CANCELLED"})
    void savesFinishedTripAndNotifiesSearch(TripStatus status) {
        trip.setStatus(status.value());
        assertThat(service.publish(7L, new CreateTravelPostRequest(12L, "海边", List.of("https://example.com/a.jpg")))).isEqualTo(50L);
        var saved = ArgumentCaptor.forClass(TravelPost.class);
        verify(repository).save(saved.capture());
        assertThat(saved.getValue().getTrip()).isSameAs(trip);
        assertThat(saved.getValue().getContent()).isEqualTo("海边");
        assertThat(JsonMapper.builder().build().readTree(saved.getValue().getPhotos()).get(0).asText())
                .isEqualTo("https://example.com/a.jpg");
        verify(events).publishEvent(new TravelPostPublishService.TravelPostPublished(
                50L, 12L, "海边", List.of("https://example.com/a.jpg")));
    }

    @ParameterizedTest @EnumSource(value = TripStatus.class, names = {"PLANNING", "ONGOING"})
    void rejectsUnfinishedTrip(TripStatus status) {
        trip.setStatus(status.value());
        failure(7L, new CreateTravelPostRequest(12L, null, null), 409);
    }

    @Test void rejectsOtherUsersTrip() { failure(8L, new CreateTravelPostRequest(12L, null, null), 403); }
    @Test void rejectsMissingTrip() { failure(7L, new CreateTravelPostRequest(99L, null, null), 404); }
    @Test void rejectsMissingIdentity() { failure(null, new CreateTravelPostRequest(12L, null, null), 401); }
    @Test void rejectsMissingTripId() { failure(7L, new CreateTravelPostRequest(null, null, null), 400); }
    @Test void rejectsZeroTripId() { failure(7L, new CreateTravelPostRequest(0L, null, null), 400); }
    @Test void rejectsLongContent() { failure(7L, new CreateTravelPostRequest(12L, "a".repeat(10001), null), 400); }
    @Test void rejectsTooManyPhotos() { failure(7L, new CreateTravelPostRequest(12L, null, Collections.nCopies(10, "https://example.com/a.jpg")), 400); }
    @ParameterizedTest @ValueSource(strings = {"file:///tmp/a.jpg", "http://example.com/a.jpg", "not-a-url"})
    void rejectsInvalidPhoto(String photo) { failure(7L, new CreateTravelPostRequest(12L, null, List.of(photo)), 400); }
    @Test void rejectsNullPhoto() { failure(7L, new CreateTravelPostRequest(12L, null, Arrays.asList((String) null)), 400); }

    @Test void optionalFieldsAreAllowed() {
        service.publish(7L, new CreateTravelPostRequest(12L, null, null));
        verify(repository).save(argThat(p -> p.getContent() == null && p.getPhotos() == null));
    }

    @Test void databaseFailureDoesNotNotifySearch() {
        when(repository.save(any())).thenThrow(new IllegalStateException("database unavailable"));
        assertThatThrownBy(() -> service.publish(7L, new CreateTravelPostRequest(12L, null, null)))
                .isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(events);
    }

    private void failure(Long userId, CreateTravelPostRequest request, int status) {
        assertThatThrownBy(() -> service.publish(userId, request)).isInstanceOfSatisfying(
                ApiError.class, e -> assertThat(e.status.value()).isEqualTo(status));
        verify(repository, never()).save(any());
        verifyNoInteractions(events);
    }
}
