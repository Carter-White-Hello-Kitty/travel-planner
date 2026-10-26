package com.travel.entity;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest
@Transactional
class EntityPersistenceIntegrationTest {

    @Autowired
    private EntityManager entityManager;

    @Test
    void persistsAndReadsSampleDataForEveryTable() {
        User user = new User();
        user.setEmail("entity-test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-password-hash");
        entityManager.persist(user);
        entityManager.flush();

        PwdResetToken resetToken = new PwdResetToken();
        resetToken.setUser(user);
        resetToken.setTokenHash(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        resetToken.setExpiresAt(LocalDateTime.now().plusHours(1));
        entityManager.persist(resetToken);

        Poi firstPoi = createPoi("test-place-" + UUID.randomUUID());
        Poi secondPoi = createPoi("test-place-" + UUID.randomUUID());

        Wishlist wishlist = new Wishlist();
        wishlist.setUser(user);
        wishlist.setPoi(firstPoi);
        wishlist.setCreatedAt(LocalDateTime.now());
        entityManager.persist(wishlist);

        Trip trip = new Trip();
        trip.setUser(user);
        trip.setStatus("Planning");
        trip.setDayCount(2);
        entityManager.persist(trip);

        TripPoi firstTripPoi = createTripPoi(trip, firstPoi, 1, 1);
        TripPoi secondTripPoi = createTripPoi(trip, secondPoi, 1, 2);

        TripLeg leg = new TripLeg();
        leg.setFromTripPoi(firstTripPoi);
        leg.setToTripPoi(secondTripPoi);
        leg.setDistanceMeters(1200);
        leg.setDurationSeconds(900);
        leg.setTransportMode("WALKING");
        entityManager.persist(leg);

        TravelPost post = new TravelPost();
        post.setTrip(trip);
        post.setContent("A sample travel journal entry.");
        post.setPhotos("photo-1.jpg,photo-2.jpg");
        entityManager.persist(post);

        entityManager.flush();
        Long userId = user.getId();
        Long resetTokenId = resetToken.getId();
        Long poiId = firstPoi.getId();
        Long wishlistId = wishlist.getId();
        Long tripId = trip.getId();
        Long tripPoiId = firstTripPoi.getId();
        Long tripLegId = leg.getId();
        Long postId = post.getId();
        String googlePlaceId = firstPoi.getGooglePlaceId();

        entityManager.clear();

        User savedUser = entityManager.find(User.class, userId);
        PwdResetToken savedResetToken = entityManager.find(PwdResetToken.class, resetTokenId);
        Poi savedPoi = entityManager.find(Poi.class, poiId);
        Wishlist savedWishlist = entityManager.find(Wishlist.class, wishlistId);
        Trip savedTrip = entityManager.find(Trip.class, tripId);
        TripPoi savedTripPoi = entityManager.find(TripPoi.class, tripPoiId);
        TripLeg savedTripLeg = entityManager.find(TripLeg.class, tripLegId);
        TravelPost savedPost = entityManager.find(TravelPost.class, postId);

        assertNotNull(savedUser);
        assertEquals(userId, savedResetToken.getUser().getId());
        assertEquals(googlePlaceId, savedPoi.getGooglePlaceId());
        assertEquals(userId, savedWishlist.getUser().getId());
        assertEquals("Planning", savedTrip.getStatus());
        assertEquals(poiId, savedTripPoi.getPoi().getId());
        assertEquals(1200, savedTripLeg.getDistanceMeters());
        assertEquals("A sample travel journal entry.", savedPost.getContent());

        System.out.println("Sample rows inserted and read back (rolled back after the test):");
        System.out.printf("users: id=%d, email=%s%n", savedUser.getId(), savedUser.getEmail());
        System.out.printf("pwd_reset_tokens: id=%d, user_id=%d%n",
                savedResetToken.getId(), savedResetToken.getUser().getId());
        System.out.printf("pois: id=%d, google_place_id=%s%n", savedPoi.getId(), savedPoi.getGooglePlaceId());
        System.out.printf("wishlist: id=%d, user_id=%d, poi_id=%d%n",
                savedWishlist.getId(), savedWishlist.getUser().getId(), savedWishlist.getPoi().getId());
        System.out.printf("trips: id=%d, user_id=%d, status=%s, day_count=%d%n",
                savedTrip.getId(), savedTrip.getUser().getId(), savedTrip.getStatus(), savedTrip.getDayCount());
        System.out.printf("trip_pois: id=%d, trip_id=%d, poi_id=%d, day=%d, sequence=%d, status=%s%n",
                savedTripPoi.getId(), savedTripPoi.getTrip().getId(), savedTripPoi.getPoi().getId(),
                savedTripPoi.getDayNumber(), savedTripPoi.getSequence(), savedTripPoi.getStatus());
        System.out.printf("trip_legs: id=%d, from_trip_poi_id=%d, to_trip_poi_id=%d, distance_meters=%d%n",
                savedTripLeg.getId(), savedTripLeg.getFromTripPoi().getId(),
                savedTripLeg.getToTripPoi().getId(), savedTripLeg.getDistanceMeters());
        System.out.printf("travel_posts: id=%d, trip_id=%d, content=%s, photos=%s%n",
                savedPost.getId(), savedPost.getTrip().getId(), savedPost.getContent(), savedPost.getPhotos());
    }

    private Poi createPoi(String googlePlaceId) {
        Poi poi = new Poi();
        poi.setGooglePlaceId(googlePlaceId);
        entityManager.persist(poi);
        return poi;
    }

    private TripPoi createTripPoi(Trip trip, Poi poi, int dayNumber, int sequence) {
        TripPoi tripPoi = new TripPoi();
        tripPoi.setTrip(trip);
        tripPoi.setPoi(poi);
        tripPoi.setDayNumber(dayNumber);
        tripPoi.setSequence(sequence);
        tripPoi.setStatus("Planned");
        entityManager.persist(tripPoi);
        return tripPoi;
    }

}
