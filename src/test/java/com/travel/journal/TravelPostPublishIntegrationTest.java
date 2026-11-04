package com.travel.journal;

import com.travel.auth.entity.User;
import com.travel.auth.service.AuthService;
import com.travel.itinerary.entity.Trip;
import com.travel.itinerary.repository.TripRepository;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/** 真实 MySQL + JWT 过滤器 + CSRF + JPA；每个用例结束回滚，不清空开发数据库。 */
@SpringBootTest(properties = "auth.worker-enabled=false")
@AutoConfigureMockMvc
@Transactional
class TravelPostPublishIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired TripRepository trips;
    @Autowired JdbcTemplate db;
    private final JsonMapper json = JsonMapper.builder().build();
    private User owner;
    private String token;

    @BeforeEach
    void setup() {
        assertEquals("travel_planner_auth_test", db.queryForObject("SELECT DATABASE()", String.class));
        String email = "journal-" + UUID.randomUUID() + "@example.com";
        owner = auth.register(email, "JournalPublish!2026");
        token = auth.login(email, "JournalPublish!2026", null).access().value();
    }

    Trip trip(String status, User user) {
        Trip trip = new Trip();
        trip.setUser(user);
        trip.setName("Seattle weekend");
        trip.setStatus(status);
        trip.setDayCount(2);
        return trips.saveAndFlush(trip);
    }

    MvcResult publish(Object body, String accessToken) throws Exception {
        MvcResult handshake = mvc.perform(get("/auth/csrf")).andReturn();
        JsonNode data = json.readTree(handshake.getResponse().getContentAsString()).path("data");
        var request = post("/travel-posts")
                .header("X-XSRF-TOKEN", data.path("token").asText())
                .cookie(handshake.getResponse().getCookie("XSRF-TOKEN"))
                .contentType("application/json")
                .content(json.writeValueAsString(body));
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn();
    }

    long count() { return db.queryForObject("SELECT COUNT(*) FROM travel_posts", Long.class); }

    @ParameterizedTest
    @ValueSource(strings = {"Completed", "Cancelled"})
    void publishesAndStoresInMysql(String status) throws Exception {
        Trip trip = trip(status, owner);
        long before = count();
        MvcResult result = publish(Map.of("trip_id", trip.getId(), "content", "A great weekend in Seattle.",
                "photos", List.of("https://example.com/seattle.jpg")), token);
        assertEquals(201, result.getResponse().getStatus());
        JsonNode response = json.readTree(result.getResponse().getContentAsString());
        assertEquals(2, response.size());
        long id = response.path("data").path("id").asLong();
        assertEquals(before + 1, count());
        assertEquals(trip.getId(), db.queryForObject("SELECT trip_id FROM travel_posts WHERE id=?", Long.class, id));
        assertEquals("A great weekend in Seattle.", db.queryForObject("SELECT content FROM travel_posts WHERE id=?", String.class, id));
        assertEquals("https://example.com/seattle.jpg", json.readTree(db.queryForObject(
                "SELECT photos FROM travel_posts WHERE id=?", String.class, id)).get(0).asText());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Planning", "Ongoing"})
    void rejectsUnfinishedTripsWithoutSaving(String status) throws Exception {
        Trip trip = trip(status, owner);
        long before = count();
        assertEquals(409, publish(Map.of("trip_id", trip.getId()), token).getResponse().getStatus());
        assertEquals(before, count());
    }

    @Test
    void rejectsOtherOwnersEvenWithForgedUserId() throws Exception {
        User other = auth.register("other-" + UUID.randomUUID() + "@example.com", "JournalPublish!2026");
        Trip trip = trip("Completed", other);
        long before = count();
        assertEquals(403, publish(Map.of("trip_id", trip.getId(), "user_id", other.getId()), token).getResponse().getStatus());
        assertEquals(before, count());
    }

    @Test
    void rejectsUnknownTrip() throws Exception {
        assertEquals(404, publish(Map.of("trip_id", Long.MAX_VALUE), token).getResponse().getStatus());
    }

    @Test
    void requiresRealAccessToken() throws Exception {
        Trip trip = trip("Completed", owner);
        long before = count();
        assertEquals(401, publish(Map.of("trip_id", trip.getId()), null).getResponse().getStatus());
        assertEquals(401, publish(Map.of("trip_id", trip.getId()), "invalid-token").getResponse().getStatus());
        assertEquals(before, count());
    }

    @Test
    void rejectsMissingTripId() throws Exception {
        assertEquals(400, publish(Map.of("content", "A great trip."), token).getResponse().getStatus());
    }

    @Test
    void rejectsInvalidPhotos() throws Exception {
        Trip trip = trip("Completed", owner);
        assertEquals(400, publish(Map.of("trip_id", trip.getId(), "photos", List.of("file:///tmp/photo.jpg")), token)
                .getResponse().getStatus());
    }

    @Test
    void allowsOptionalFields() throws Exception {
        Trip trip = trip("Completed", owner);
        MvcResult result = publish(Map.of("trip_id", trip.getId()), token);
        assertEquals(201, result.getResponse().getStatus());
        long id = json.readTree(result.getResponse().getContentAsString()).path("data").path("id").asLong();
        assertNull(db.queryForObject("SELECT photos FROM travel_posts WHERE id=?", String.class, id));
    }

    @Test
    void requiresCsrfWithoutSaving() throws Exception {
        Trip trip = trip("Completed", owner);
        long before = count();
        MvcResult result = mvc.perform(post("/travel-posts").header("Authorization", "Bearer " + token)
                .contentType("application/json").content("{\"trip_id\":" + trip.getId() + "}")).andReturn();
        assertEquals(403, result.getResponse().getStatus());
        assertEquals(before, count());
    }
}
