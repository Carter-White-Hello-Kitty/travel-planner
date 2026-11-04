package com.travel.journal.service.impl;

import com.travel.journal.service.TravelPostPublishService;

import com.travel.journal.entity.TravelPost;
import com.travel.itinerary.entity.Trip;
import com.travel.journal.repository.TravelPostRepository;
import com.travel.journal.vo.CreateTravelPostRequest;
import jakarta.persistence.EntityManager;
import jakarta.persistence.LockModeType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.travel.common.ApiError;
import tools.jackson.databind.ObjectMapper;

@Service
public class TravelPostPublishServiceImpl implements TravelPostPublishService {
    private final EntityManager entityManager;
    private final TravelPostRepository postRepository;
    private final ObjectMapper objectMapper;
    private final ApplicationEventPublisher eventPublisher;

    public TravelPostPublishServiceImpl(EntityManager entityManager, TravelPostRepository postRepository,
            ObjectMapper objectMapper, ApplicationEventPublisher eventPublisher) {
        this.entityManager = entityManager;
        this.postRepository = postRepository;
        this.objectMapper = objectMapper;
        this.eventPublisher = eventPublisher;
    }

    @Override
    @Transactional
    public Long publish(Long currentUserId, CreateTravelPostRequest request) {
        if (currentUserId == null || currentUserId <= 0) {
            throw new ApiError(HttpStatus.UNAUTHORIZED, "AUTH_REQUIRED", "Please log in.");
        }
        validate(request);

        Trip trip = entityManager.find(Trip.class, request.trip_id(), LockModeType.PESSIMISTIC_WRITE);
        if (trip == null) {
            throw new ApiError(HttpStatus.NOT_FOUND, "TRIP_NOT_FOUND", "Trip not found.");
        }
        if (trip.getUser() == null || !currentUserId.equals(trip.getUser().getId())) {
            throw new ApiError(HttpStatus.FORBIDDEN, "FORBIDDEN", "This trip belongs to another user.");
        }
        if (!"Completed".equals(trip.getStatus()) && !"Cancelled".equals(trip.getStatus())) {
            throw new ApiError(HttpStatus.CONFLICT, "TRIP_NOT_FINISHED",
                    "Only completed or cancelled trips can be posted.");
        }

        TravelPost post = new TravelPost();
        post.setTrip(trip);
        post.setContent(request.content());

        String photosJson = request.photos() == null ? null : objectMapper.writeValueAsString(request.photos());
        if (photosJson != null && photosJson.getBytes(StandardCharsets.UTF_8).length > 60000) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Photo URLs are too long.");
        }
        post.setPhotos(photosJson);
        TravelPost saved = postRepository.save(post);


        eventPublisher.publishEvent(new TravelPostPublished(
                saved.getId(), trip.getId(), request.content(), request.photos()));
        return saved.getId();
    }

    private void validate(CreateTravelPostRequest request) {
        if (request == null || request.trip_id() == null || request.trip_id() <= 0) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "A valid trip_id is required.");
        }
        if (request.content() != null && request.content().length() > 10000) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Content is too long.");
        }
        if (request.photos() == null) return;
        if (request.photos().size() > 9) {
            throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "At most 9 photos are allowed.");
        }
        for (String photo : request.photos()) {
            try {
                if (photo == null || photo.length() > 2048) throw new IllegalArgumentException();
                URI uri = URI.create(photo);
                if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                        || uri.getUserInfo() != null) throw new IllegalArgumentException();
            } catch (IllegalArgumentException e) {
                throw new ApiError(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid photo URL.");
            }
        }
    }
}
