package com.travel.entity;

import com.travel.repositories.PoiRepository;
import com.travel.repositories.PwdResetTokenRepository;
import com.travel.repositories.TravelPostRepository;
import com.travel.repositories.TripDayRepository;
import com.travel.repositories.TripPoiRepository;
import com.travel.repositories.TripRepository;
import com.travel.repositories.UserRepository;
import com.travel.repositories.WishlistRepository;
import jakarta.persistence.EntityManager;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

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

    @Autowired
    private EntityManager entityManager;
}
