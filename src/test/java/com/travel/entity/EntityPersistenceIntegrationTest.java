package com.travel.entity;

import com.travel.repositories.PoiRepository;
import com.travel.repositories.PwdResetTokenRepository;
import com.travel.repositories.TravelPostRepository;
import com.travel.repositories.TripDayRepository;
import com.travel.repositories.TripPoiRepository;
import com.travel.repositories.TripRepository;
import com.travel.repositories.UserRepository;
import com.travel.repositories.WishlistRepository;
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
    private UserRepository userRepository;

    @Autowired
    private PwdResetTokenRepository pwdResetTokenRepository;

    @Autowired
    private PoiRepository poiRepository;

    @Autowired
    private WishlistRepository wishlistRepository;

    @Autowired
    private TripRepository tripRepository;

    @Autowired
    private TripDayRepository tripDayRepository;

    @Autowired
    private TripPoiRepository tripPoiRepository;

    @Autowired
    private TravelPostRepository travelPostRepository;

    @Test
    void persistsAndReadsSampleDataForEveryTable() {
        User user = new User();
        user.setEmail("entity-test-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("test-password-hash");
        user = userRepository.saveAndFlush(user);

        PwdResetToken resetToken = new PwdResetToken();
        resetToken.setUser(user);
        resetToken.setTokenHash(UUID.randomUUID().toString().replace("-", "")
                + UUID.randomUUID().toString().replace("-", ""));
        resetToken.setExpiresAt(LocalDateTime.now().plusHours(1));
        resetToken = pwdResetTokenRepository.saveAndFlush(resetToken);

        Poi firstPoi = poiRepository.saveAndFlush(createPoi("test-place-" + UUID.randomUUID()));
        Poi secondPoi = poiRepository.saveAndFlush(createPoi("test-place-" + UUID.randomUUID()));

        Wishlist wishlist = new Wishlist();
        wishlist.setUser(user);
        wishlist.setPoi(firstPoi);
        wishlist.setCreatedAt(LocalDateTime.now());
        wishlist = wishlistRepository.saveAndFlush(wishlist);

        Trip trip = new Trip();
        trip.setUser(user);
        trip.setName("Tianjin Weekend");
        trip.setStatus("Planning");
        trip.setDayCount(2);
        trip = tripRepository.saveAndFlush(trip);

        TripDay tripDay = new TripDay();
        tripDay.setTrip(trip);
        tripDay = tripDayRepository.saveAndFlush(tripDay);

        TripPoi firstTripPoi = tripPoiRepository.saveAndFlush(createTripPoi(tripDay, firstPoi, 1));
        tripPoiRepository.saveAndFlush(createTripPoi(tripDay, secondPoi, 2));

        TravelPost post = new TravelPost();
        post.setTrip(trip);
        post.setContent("A sample travel journal entry.");
        post.setPhotos("photo-1.jpg,photo-2.jpg");
        post = travelPostRepository.saveAndFlush(post);

        User savedUser = userRepository.findById(user.getId()).orElseThrow();
        PwdResetToken savedResetToken = pwdResetTokenRepository.findById(resetToken.getId()).orElseThrow();
        Poi savedPoi = poiRepository.findById(firstPoi.getId()).orElseThrow();
        Wishlist savedWishlist = wishlistRepository.findById(wishlist.getId()).orElseThrow();
        Trip savedTrip = tripRepository.findById(trip.getId()).orElseThrow();
        TripDay savedTripDay = tripDayRepository.findById(tripDay.getId()).orElseThrow();
        TripPoi savedTripPoi = tripPoiRepository.findById(firstTripPoi.getId()).orElseThrow();
        TravelPost savedPost = travelPostRepository.findById(post.getId()).orElseThrow();

        assertNotNull(savedUser.getId());
        assertEquals(savedUser.getId(), savedResetToken.getUser().getId());
        assertEquals(firstPoi.getGooglePlaceId(), savedPoi.getGooglePlaceId());
        assertEquals(savedUser.getId(), savedWishlist.getUser().getId());
        assertEquals("Tianjin Weekend", savedTrip.getName());
        assertEquals("Planning", savedTrip.getStatus());
        assertEquals(savedTrip.getId(), savedTripDay.getTrip().getId());
        assertEquals(firstPoi.getId(), savedTripPoi.getPoi().getId());
        assertEquals(savedTripDay.getId(), savedTripPoi.getTripDay().getId());
        assertEquals("A sample travel journal entry.", savedPost.getContent());

        System.out.println("Sample rows inserted and read back through Spring Data JPA (rolled back after the test):");
        System.out.printf("users: id=%d, email=%s%n", savedUser.getId(), savedUser.getEmail());
        System.out.printf("pwd_reset_tokens: id=%d, user_id=%d%n",
                savedResetToken.getId(), savedResetToken.getUser().getId());
        System.out.printf("pois: id=%d, google_place_id=%s%n", savedPoi.getId(), savedPoi.getGooglePlaceId());
        System.out.printf("wishlist: id=%d, user_id=%d, poi_id=%d%n",
                savedWishlist.getId(), savedWishlist.getUser().getId(), savedWishlist.getPoi().getId());
        System.out.printf("trips: id=%d, user_id=%d, name=%s, status=%s, day_count=%d%n",
                savedTrip.getId(), savedTrip.getUser().getId(), savedTrip.getName(),
                savedTrip.getStatus(), savedTrip.getDayCount());
        System.out.printf("trip_days: id=%d, trip_id=%d%n",
                savedTripDay.getId(), savedTripDay.getTrip().getId());
        System.out.printf("trip_pois: id=%d, poi_id=%d, trip_day_id=%d, sequence=%d, status=%s%n",
                savedTripPoi.getId(), savedTripPoi.getPoi().getId(),
                savedTripPoi.getTripDay().getId(), savedTripPoi.getSequence(), savedTripPoi.getStatus());
        System.out.printf("travel_posts: id=%d, trip_id=%d, content=%s, photos=%s%n",
                savedPost.getId(), savedPost.getTrip().getId(), savedPost.getContent(), savedPost.getPhotos());
    }

    private static Poi createPoi(String googlePlaceId) {
        Poi poi = new Poi();
        poi.setGooglePlaceId(googlePlaceId);
        return poi;
    }

    private static TripPoi createTripPoi(TripDay tripDay, Poi poi, int sequence) {
        TripPoi tripPoi = new TripPoi();
        tripPoi.setTripDay(tripDay);
        tripPoi.setPoi(poi);
        tripPoi.setSequence(sequence);
        tripPoi.setStatus("Planned");
        return tripPoi;
    }
}
