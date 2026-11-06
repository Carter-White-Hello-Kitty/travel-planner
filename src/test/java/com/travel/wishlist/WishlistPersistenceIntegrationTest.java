package com.travel.wishlist;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.travel.auth.entity.User;
import com.travel.auth.repository.UserRepository;
import com.travel.wishlist.entity.Wishlist;
import com.travel.wishlist.repository.WishlistRepository;
import jakarta.persistence.EntityManager;
import java.time.LocalDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@Transactional
class WishlistPersistenceIntegrationTest {
    @Autowired UserRepository users;
    @Autowired WishlistRepository wishlist;
    @Autowired EntityManager entityManager;

    @Test
    void storesGooglePlaceIdWithoutALocalPoiRow() {
        User user = new User();
        user.setEmail("wishlist-" + UUID.randomUUID() + "@example.com");
        user.setPasswordHash("unused-in-persistence-test");
        users.save(user);

        Wishlist item = new Wishlist();
        item.setUser(user);
        item.setGooglePlaceId("google-place-id-101");
        item.setCreatedAt(LocalDateTime.of(2026, 11, 6, 12, 0));
        Long id = wishlist.save(item).getId();

        entityManager.flush();
        entityManager.clear();
        Wishlist saved = wishlist.findById(id).orElseThrow();
        assertEquals("google-place-id-101", saved.getGooglePlaceId());
        assertEquals(user.getId(), saved.getUser().getId());
    }
}
