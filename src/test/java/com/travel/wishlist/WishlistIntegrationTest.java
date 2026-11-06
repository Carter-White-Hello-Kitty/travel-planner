package com.travel.wishlist;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.travel.poisearch.entity.Poi;
import com.travel.poisearch.repository.PoiRepository;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
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
    @Autowired PoiRepository poiRepository;
    final JsonMapper json = JsonMapper.builder().build();

    static final String PASSWORD = "TravelPlanner!2026",
            ORIGIN = "http://localhost:3000";

    String token;

    @BeforeEach
    void setUp() throws Exception {
        clean();
        token = registerAndLogin("wishlist@example.com");
    }

    @AfterEach
    void clean() {
        String name = db.queryForObject("SELECT DATABASE()", String.class);
        if (!"travel_planner_test".equals(name))
            throw new IllegalStateException("Refusing to clean a non-test database");
        for (String table : List.of(
                "wishlist",
                "pwd_reset_tokens", "auth_mail_jobs", "auth_rate_limits",
                "auth_refresh_tokens", "auth_sessions", "users"))
            db.update("DELETE FROM " + table);
        db.update("DELETE FROM pois WHERE google_place_id LIKE 'wishlist-test-%'");
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
        assertEquals(0, body.get("data").get("pagination").get("totalPages").asInt());
    }

    @Test
    void addsPoiAndReturnsNarrowedItem() throws Exception {
        Long poiId = poi("central-park");
        MockHttpServletResponse added = addCall(poiId, token);

        assertEquals(201, added.getStatus());
        JsonNode addedBody = json.readTree(added.getContentAsString());
        assertEquals("POI added to wishlist successfully", addedBody.get("message").asText());
        assertEquals(poiId.longValue(), addedBody.get("data").get("poiId").asLong());
        assertFalse(addedBody.get("data").get("createdAt").asText().isBlank());
        assertEquals(1, count());

        JsonNode item = json.readTree(listCall("", token).getContentAsString())
                .get("data").get("items").get(0);
        assertEquals(poiId.longValue(), item.get("poiId").asLong());
        assertTrue(item.get("googlePlaceId").asText().startsWith("wishlist-test-"));
        assertTrue(item.has("createdAt"));

        // 收窄后的契约：不再返回 POI 详情字段
        for (String removed : List.of("name", "description", "address",
                "latitude", "longitude", "imageUrl"))
            assertFalse(item.has(removed), "response must not contain " + removed);
    }

    @Test
    void removesPoi() throws Exception {
        Long poiId = poi("remove-me");
        assertEquals(201, addCall(poiId, token).getStatus());

        MockHttpServletResponse removed = deleteCall("/wishlist/" + poiId, token);

        assertEquals(200, removed.getStatus());
        JsonNode body = json.readTree(removed.getContentAsString());
        assertEquals("POI removed from wishlist successfully", body.get("message").asText());
        assertEquals(poiId.longValue(), body.get("data").get("poiId").asLong());
        assertEquals(0, count());
    }

    // ---- 业务异常 ----

    @Test
    void duplicateAddIsConflict() throws Exception {
        Long poiId = poi("duplicate");

        assertEquals(201, addCall(poiId, token).getStatus());
        assertError(addCall(poiId, token), 409, "WISHLIST_ITEM_ALREADY_EXISTS");
        assertEquals(1, count());
    }

    @Test
    void addingMissingPoiIsNotFound() throws Exception {
        assertError(addCall(999_999L, token), 404, "RESOURCE_NOT_FOUND");
        assertEquals(0, count());
    }

    @Test
    void removingUnknownItemIsNotFound() throws Exception {
        assertError(deleteCall("/wishlist/999999", token), 404, "WISHLIST_ITEM_NOT_FOUND");
    }

    @Test
    void cannotSeeOrRemoveAnotherUsersItem() throws Exception {
        String other = registerAndLogin("wishlist-other@example.com");
        Long poiId = poi("private");
        assertEquals(201, addCall(poiId, token).getStatus());

        assertTrue(json.readTree(listCall("", other).getContentAsString())
                .get("data").get("items").isEmpty());
        assertError(deleteCall("/wishlist/" + poiId, other), 404, "WISHLIST_ITEM_NOT_FOUND");
        assertEquals(1, count());
    }

    // ---- 排序与分页 ----

    @Test
    void newerItemsComeFirstByDefaultAndCanAscend() throws Exception {
        Long a = poi("a"), b = poi("b"), c = poi("c");
        for (Long id : List.of(a, b, c)) assertEquals(201, addCall(id, token).getStatus());

        assertEquals(List.of(c, b, a), ids(listCall("", token)));
        assertEquals(List.of(a, b, c), ids(listCall("?order=asc", token)));
        assertEquals(List.of(c, b, a), ids(listCall("?sort=createdAt&order=desc", token)));
    }

    @Test
    void paginates() throws Exception {
        Long a = poi("p1"), b = poi("p2"), c = poi("p3");
        for (Long id : List.of(a, b, c)) assertEquals(201, addCall(id, token).getStatus());

        JsonNode first = json.readTree(listCall("?page=1&pageSize=2", token).getContentAsString())
                .get("data");
        assertEquals(2, first.get("items").size());
        assertEquals(3, first.get("pagination").get("totalItems").asLong());
        assertEquals(2, first.get("pagination").get("totalPages").asInt());
        assertEquals(1, first.get("pagination").get("page").asInt());
        assertEquals(2, first.get("pagination").get("pageSize").asInt());

        JsonNode second = json.readTree(listCall("?page=2&pageSize=2", token).getContentAsString())
                .get("data");
        assertEquals(1, second.get("items").size());
        assertEquals(2, second.get("pagination").get("page").asInt());
    }

    // ---- 鉴权 / CSRF / 参数校验 ----

    @Test
    void requestsWithoutTokenAreUnauthorized() throws Exception {
        assertError(listCall("", null), 401, "AUTH_REQUIRED");
        assertError(addCall(poi("no-token"), null), 401, "AUTH_REQUIRED");
        assertError(deleteCall("/wishlist/1", null), 401, "AUTH_REQUIRED");
    }

    @Test
    void writeRequestsRequireCsrf() throws Exception {
        Long poiId = poi("csrf");
        MockHttpServletResponse r = mvc.perform(post("/wishlist")
                        .header("Authorization", "Bearer " + token)
                        .contentType("application/json")
                        .content(json.writeValueAsString(Map.of("poiId", poiId))))
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
        assertError(addRaw("{\"poiId\":\"103\"}", token), 400, "INVALID_REQUEST");
        assertError(addRaw("{\"poiId\":103,\"extra\":true}", token), 400, "INVALID_REQUEST");
        assertError(deleteCall("/wishlist/abc", token), 400, "INVALID_REQUEST");
        assertError(deleteCall("/wishlist/0", token), 400, "INVALID_REQUEST");
        assertEquals(0, count());
    }

    // ---- helpers ----

    Long poi(String suffix) {
        Poi poi = new Poi();
        poi.setGooglePlaceId("wishlist-test-" + suffix + "-" + UUID.randomUUID());
        return poiRepository.save(poi).getId();
    }

    int count() {
        return db.queryForObject("SELECT COUNT(*) FROM wishlist", Integer.class);
    }

    List<Long> ids(MockHttpServletResponse r) throws Exception {
        List<Long> ids = new ArrayList<>();
        json.readTree(r.getContentAsString()).get("data").get("items")
                .forEach(item -> ids.add(item.get("poiId").asLong()));
        return ids;
    }

    MockHttpServletResponse listCall(String query, String accessToken) throws Exception {
        MockHttpServletRequestBuilder request = get("/wishlist" + query);
        if (accessToken != null) request.header("Authorization", "Bearer " + accessToken);
        return mvc.perform(request).andReturn().getResponse();
    }

    MockHttpServletResponse addCall(Long poiId, String accessToken) throws Exception {
        return addRaw(json.writeValueAsString(Map.of("poiId", poiId)), accessToken);
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