package com.travel.wishlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.travel.common.ApiError;
import com.travel.auth.config.Secrets;
import com.travel.poisearch.client.GooglePlacesClient;
import com.travel.poisearch.vo.PoiVO;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** GET/POST/DELETE /wishlist over real HTTP, auth, CSRF and the test database. */
@SpringBootTest
@AutoConfigureMockMvc
class WishlistIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate db;
    @Autowired Secrets secrets;
    @MockitoBean GooglePlacesClient google;
    final JsonMapper json = JsonMapper.builder().build();

    static final String PASSWORD = "TravelPlanner!2026",
            ORIGIN = "http://localhost:3000",
            EMAIL = "wishlist@example.com",
            OTHER_EMAIL = "wishlist-other@example.com",
            TEST_REMOTE_ADDR = "198.51.100.42";

    String token;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        // 默认：任何 placeId 都能从 Google 取到详情
        when(google.details(anyString())).thenAnswer(inv -> place(inv.getArgument(0)));
        token = registerAndLogin(EMAIL);
    }

    @AfterEach
    void clean() {
        String name = db.queryForObject("SELECT DATABASE()", String.class);
        if (!"travel_planner_test".equals(name))
            throw new IllegalStateException("Refusing to clean a non-test database");
        for (Long userId : db.queryForList(
                "SELECT id FROM users WHERE email IN (?, ?)", Long.class, EMAIL, OTHER_EMAIL)) {
            db.update("DELETE FROM wishlist WHERE user_id = ?", userId);
            db.update("DELETE FROM pwd_reset_tokens WHERE user_id = ?", userId);
            db.update("DELETE FROM auth_refresh_tokens WHERE session_id IN "
                    + "(SELECT id FROM auth_sessions WHERE user_id = ?)", userId);
            db.update("DELETE FROM auth_sessions WHERE user_id = ?", userId);
            db.update("DELETE FROM users WHERE id = ?", userId);
            for (String action : List.of("list", "add", "remove"))
                deleteRateBucket("wishlist:" + action + ":" + userId);
        }
        db.update("DELETE FROM auth_mail_jobs WHERE email IN (?, ?)", EMAIL, OTHER_EMAIL);
        deleteRateBucket("register:" + TEST_REMOTE_ADDR);
        deleteRateBucket("login-ip:" + TEST_REMOTE_ADDR);
    }

    private void deleteRateBucket(String key) {
        db.update("DELETE FROM auth_rate_limits WHERE bucket_key LIKE ?", secrets.rateHash(key) + ":%");
    }

    // ---- 主流程 ----

    @Test
    void emptyWishlistReturnsEmptyPage() throws Exception {
        MockHttpServletResponse r = listCall("", token);

        assertEquals(200, r.getStatus());
        JsonNode body = json.readTree(r.getContentAsString());
        assertEquals("Wishlist retrieved successfully", body.get("message").asText());
        assertTrue(body.get("data").get("items").isEmpty());
        assertEquals(0, body.get("data").get("pagination").get("totalItems").asLong());
    }

    @Test
    void cleanupPreservesAnotherUsersWishlist() {
        String email = "unrelated-wishlist-" + UUID.randomUUID() + "@example.com";
        db.update("INSERT INTO users (email, password_hash) VALUES (?, ?)", email, "unused");
        Long userId = db.queryForObject("SELECT id FROM users WHERE email = ?", Long.class, email);
        db.update("INSERT INTO wishlist (user_id, google_place_id, created_at) VALUES (?, ?, ?)",
                userId, "unrelated-place", LocalDateTime.now());
        try {
            clean();
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM users WHERE id = ?", Integer.class, userId));
            assertEquals(1, db.queryForObject("SELECT COUNT(*) FROM wishlist WHERE user_id = ?", Integer.class, userId));
        } finally {
            db.update("DELETE FROM wishlist WHERE user_id = ?", userId);
            db.update("DELETE FROM users WHERE id = ?", userId);
        }
    }

    @Test
    void addsPlaceAndReturnsDisplayFields() throws Exception {
        MockHttpServletResponse added = addCall("place-central", token);

        assertEquals(201, added.getStatus());
        JsonNode addedBody = json.readTree(added.getContentAsString());
        assertEquals("POI added to wishlist successfully", addedBody.get("message").asText());
        assertEquals("place-central", addedBody.get("data").get("googlePlaceId").asText());
        assertFalse(addedBody.get("data").get("createdAt").asText().isBlank());
        assertEquals(1, count());

        JsonNode item = json.readTree(listCall("", token).getContentAsString())
                .get("data").get("items").get(0);
        assertEquals("place-central", item.get("googlePlaceId").asText());
        assertEquals("Place place-central", item.get("name").asText());
        assertEquals(40.75, item.get("latitude").asDouble(), 0.0001);
        assertEquals("museum", item.get("category").asText());
        assertTrue(item.has("createdAt"));
        assertFalse(item.has("poiId"));
    }

    @Test
    void removesPlace() throws Exception {
        assertEquals(201, addCall("place-remove", token).getStatus());

        MockHttpServletResponse removed = deleteCall("/wishlist/place-remove", token);

        assertEquals(200, removed.getStatus());
        JsonNode body = json.readTree(removed.getContentAsString());
        assertEquals("POI removed from wishlist successfully", body.get("message").asText());
        assertEquals("place-remove", body.get("data").get("googlePlaceId").asText());
        assertEquals(0, count());
    }

    // ---- 业务异常 ----

    @Test
    void duplicateAddIsConflict() throws Exception {
        assertEquals(201, addCall("place-dup", token).getStatus());

        assertError(addCall("place-dup", token), 409, "WISHLIST_ITEM_ALREADY_EXISTS");
        assertEquals(1, count());
    }

    @Test
    void addingUnknownPlaceIsNotFound() throws Exception {
        when(google.details("missing-place"))
                .thenThrow(ApiError.invalid("googlePlaceId", "Google Place ID not found"));

        assertError(addCall("missing-place", token), 404, "RESOURCE_NOT_FOUND");
        assertEquals(0, count());
    }

    @Test
    void removingUnknownItemIsNotFound() throws Exception {
        assertError(deleteCall("/wishlist/never-added", token), 404, "WISHLIST_ITEM_NOT_FOUND");
    }

    @Test
    void removingAnotherUsersItemIsNotFound() throws Exception {
        String other = registerAndLogin(OTHER_EMAIL);
        assertEquals(201, addCall("place-private", token).getStatus());

        assertError(deleteCall("/wishlist/place-private", other), 404, "WISHLIST_ITEM_NOT_FOUND");
        assertEquals(1, count());
    }

    @Test
    void googleOutageSurfacesServiceUnavailable() throws Exception {
        when(google.details("down-place"))
                .thenThrow(new ApiError(HttpStatus.SERVICE_UNAVAILABLE,
                        "POI_SEARCH_NOT_CONFIGURED", "POI search is not configured"));

        assertError(addCall("down-place", token), 503, "POI_SEARCH_NOT_CONFIGURED");
        assertEquals(0, count());
    }

    @Test
    void removedPlaceStillListsWithoutDisplayFields() throws Exception {
        Long userId = db.queryForObject(
                "SELECT id FROM users WHERE email = ?", Long.class, EMAIL);
        db.update(
                "INSERT INTO wishlist (user_id, google_place_id, created_at) VALUES (?, ?, ?)",
                userId, "gone-place", LocalDateTime.now());
        when(google.details("gone-place"))
                .thenThrow(ApiError.invalid("googlePlaceId", "Google Place ID not found"));

        MockHttpServletResponse r = listCall("", token);
        assertEquals(200, r.getStatus());
        JsonNode item = json.readTree(r.getContentAsString())
                .get("data").get("items").get(0);
        assertEquals("gone-place", item.get("googlePlaceId").asText());
        assertTrue(item.path("name").isNull() || item.path("name").isMissingNode());
    }

    // ---- 排序与分页 ----

    @Test
    void newerItemsComeFirstByDefaultAndCanAscend() throws Exception {
        for (String id : List.of("place-a", "place-b", "place-c"))
            assertEquals(201, addCall(id, token).getStatus());

        assertEquals(List.of("place-c", "place-b", "place-a"), ids(listCall("", token)));
        assertEquals(List.of("place-a", "place-b", "place-c"),
                ids(listCall("?order=asc", token)));
    }

    @Test
    void paginates() throws Exception {
        for (String id : List.of("place-p1", "place-p2", "place-p3"))
            assertEquals(201, addCall(id, token).getStatus());

        JsonNode first = json.readTree(listCall("?page=1&pageSize=2", token).getContentAsString())
                .get("data");
        assertEquals(2, first.get("items").size());
        assertEquals(3, first.get("pagination").get("totalItems").asLong());
        assertEquals(2, first.get("pagination").get("totalPages").asInt());

        JsonNode second = json.readTree(listCall("?page=2&pageSize=2", token).getContentAsString())
                .get("data");
        assertEquals(1, second.get("items").size());
        assertEquals(2, second.get("pagination").get("page").asInt());
    }

    // ---- 鉴权 / CSRF / 参数校验 ----

    @Test
    void requestsWithoutTokenAreUnauthorized() throws Exception {
        assertError(listCall("", null), 401, "AUTH_REQUIRED");
        assertError(addCall("place-no-token", null), 401, "AUTH_REQUIRED");
        assertError(deleteCall("/wishlist/place-1", null), 401, "AUTH_REQUIRED");
    }

    @Test
    void writeRequestsRequireCsrf() throws Exception {
        MockHttpServletResponse r = mvc.perform(post("/wishlist")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("googlePlaceId", "place-csrf"))))
                .andReturn().getResponse();

        assertEquals(403, r.getStatus());
        assertEquals("CSRF_REJECTED",
                json.readTree(r.getContentAsString()).get("error").get("code").asText());
        assertEquals(0, count());
    }

    @Test
    void rejectsInvalidQueryAndBodyParameters() throws Exception {
        assertError(listCall("?page=0", token), 400, "INVALID_REQUEST");
        assertError(listCall("?pageSize=0", token), 400, "INVALID_REQUEST");
        assertError(listCall("?pageSize=101", token), 400, "INVALID_REQUEST");
        assertError(listCall("?sort=name", token), 400, "INVALID_REQUEST");
        assertError(listCall("?order=up", token), 400, "INVALID_REQUEST");

        assertError(addRaw("{}", token), 400, "INVALID_REQUEST");
        assertError(addRaw("{\"googlePlaceId\":\"   \"}", token), 400, "INVALID_REQUEST");
        assertError(addRaw("{\"googlePlaceId\":123}", token), 400, "INVALID_REQUEST");
        assertError(addRaw("{\"googlePlaceId\":\"a\",\"extra\":true}", token), 400, "INVALID_REQUEST");
        assertError(deleteCall("/wishlist/" + "x".repeat(226), token), 400, "INVALID_REQUEST");
        assertEquals(0, count());
    }

    // ---- helpers ----

    static PoiVO place(String id) {
        return new PoiVO(id, "Place " + id, "New York, NY", 40.75, -73.99,
                "museum", 4.5, "museum", List.of("museum"), 100L, "OPERATIONAL");
    }

    int count() {
        return db.queryForObject("SELECT COUNT(*) FROM wishlist w JOIN users u ON u.id = w.user_id "
                + "WHERE u.email = ?", Integer.class, EMAIL);
    }

    List<String> ids(MockHttpServletResponse r) throws Exception {
        List<String> ids = new ArrayList<>();
        json.readTree(r.getContentAsString()).get("data").get("items")
                .forEach(item -> ids.add(item.get("googlePlaceId").asText()));
        return ids;
    }

    MockHttpServletResponse listCall(String query, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = get("/wishlist" + query);
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn().getResponse();
    }

    MockHttpServletResponse addCall(String googlePlaceId, String accessToken) throws Exception {
        return addRaw(json.writeValueAsString(Map.of("googlePlaceId", googlePlaceId)), accessToken);
    }

    MockHttpServletResponse addRaw(String body, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(post("/wishlist"))
                .contentType("application/json").content(body);
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn().getResponse();
    }

    MockHttpServletResponse deleteCall(String path, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(delete(path));
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn().getResponse();
    }

    String registerAndLogin(String email) throws Exception {
        Map<String, String> credentials = Map.of("email", email, "password", PASSWORD);
        assertEquals(201, authPost("/auth/register", credentials).getStatus());
        MockHttpServletResponse login = authPost("/auth/login", credentials);
        assertEquals(200, login.getStatus());
        return json.readTree(login.getContentAsString())
                .get("data").get("accessToken").asText();
    }

    MockHttpServletResponse authPost(String path, Object body) throws Exception {
        MockHttpServletRequestBuilder request = withCsrf(post(path))
                .with(req -> { req.setRemoteAddr(TEST_REMOTE_ADDR); return req; })
                .header("Origin", ORIGIN)
                .header("X-Requested-With", "XMLHttpRequest")
                .contentType("application/json")
                .content(json.writeValueAsString(body));
        return mvc.perform(request).andReturn().getResponse();
    }

    MockHttpServletRequestBuilder withCsrf(MockHttpServletRequestBuilder request) throws Exception {
        MvcResult handshake = mvc.perform(get("/auth/csrf")).andReturn();
        String csrf = json.readTree(handshake.getResponse().getContentAsString())
                .get("data").get("token").asText();
        return request.header("X-XSRF-TOKEN", csrf)
                .cookie(handshake.getResponse().getCookie("XSRF-TOKEN"));
    }

    void assertError(MockHttpServletResponse r, int status, String code) throws Exception {
        assertEquals(status, r.getStatus(), r.getContentAsString());
        assertEquals(code, json.readTree(r.getContentAsString())
                .get("error").get("code").asText());
    }
}
