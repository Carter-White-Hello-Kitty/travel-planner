package com.travel.auth;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

import jakarta.servlet.http.Cookie;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.*;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(
    properties = {
      "auth.development-keys=true",
      "auth.cookie-secure=false",
      "auth.worker-enabled=false"
    })
@AutoConfigureMockMvc
class AuthIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate db;
  @Autowired AuthService auth;
  @Autowired MailWorker worker;
  @Autowired TestClock clock;
  @Autowired TokenService jwt;
  @MockitoBean JavaMailSender mail;
  final JsonMapper json = JsonMapper.builder().build();
  static final String PASSWORD = "TravelPlanner!2026",
      EMAIL = "user@example.com",
      ORIGIN = "http://localhost:3000";

  @TestConfiguration
  static class Config {
    @Bean
    @Primary
    TestClock testClock() {
      return new TestClock();
    }
  }

  static class TestClock extends Clock {
    Instant now = Instant.parse("2026-10-25T12:00:00Z");

    public ZoneId getZone() {
      return ZoneOffset.UTC;
    }

    public Clock withZone(ZoneId z) {
      return this;
    }

    public Instant instant() {
      return now;
    }

    void advance(long seconds) {
      now = now.plusSeconds(seconds);
    }
  }

  @BeforeEach
  void clean() {
    String name = db.queryForObject("SELECT DATABASE()", String.class);
    if (!"travel_planner_auth_test".equals(name))
      throw new IllegalStateException("Refusing to clean a non-test database");
    for (String table :
        List.of(
            "auth_mail_jobs",
            "auth_rate_limits",
            "pwd_reset_tokens",
            "auth_refresh_tokens",
            "auth_sessions",
            "users")) db.update("DELETE FROM " + table);
    clock.now = Instant.parse("2026-10-25T12:00:00Z");
    reset(mail);
  }

  ResultActions postJson(String path, Object value, Cookie... cookies) throws Exception {
    var handshake = mvc.perform(get("/auth/csrf")).andReturn();
    var request =
        post(path)
            .header("X-XSRF-TOKEN", data(handshake).get("token").asText())
            .cookie(handshake.getResponse().getCookie("XSRF-TOKEN"))
            .header("Origin", ORIGIN)
            .header("X-Requested-With", "XMLHttpRequest")
            .contentType("application/json")
            .content(json.writeValueAsString(value));
    if (cookies.length > 0) request.cookie(cookies);
    return mvc.perform(request);
  }

  JsonNode data(MvcResult result) throws Exception {
    return json.readTree(result.getResponse().getContentAsString()).get("data");
  }

  Cookie cookie(MvcResult r) {
    Cookie c = r.getResponse().getCookie("refresh_token");
    assertNotNull(c);
    return c;
  }

  void register() throws Exception {
    assertEquals(
        201,
        postJson("/auth/register", Map.of("email", EMAIL, "password", PASSWORD))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  MvcResult login() throws Exception {
    return postJson("/auth/login", Map.of("email", EMAIL, "password", PASSWORD)).andReturn();
  }

  int me(String token) throws Exception {
    return mvc.perform(get("/auth/me").header("Authorization", "Bearer " + token))
        .andReturn()
        .getResponse()
        .getStatus();
  }

  String access(MvcResult r) throws Exception {
    return data(r).get("accessToken").asText();
  }

  String resetToken() throws Exception {
    assertEquals(
        202,
        postJson("/auth/request-reset-pwd", Map.of("email", EMAIL))
            .andReturn()
            .getResponse()
            .getStatus());
    worker.processOne();
    var capture = org.mockito.ArgumentCaptor.forClass(SimpleMailMessage.class);
    verify(mail, atLeastOnce()).send(capture.capture());
    Matcher m = Pattern.compile("#token=([A-Za-z0-9_-]{43})").matcher(capture.getValue().getText());
    assertTrue(m.find());
    return m.group(1);
  }

  @Test
  void registerNormalizesAndHashes() throws Exception {
    var result =
        postJson("/auth/register", Map.of("email", " USER@Example.com ", "password", PASSWORD))
            .andReturn();
    assertEquals(201, result.getResponse().getStatus());
    assertEquals(EMAIL, data(result).get("user").get("email").asText());
    String hash = db.queryForObject("SELECT password_hash FROM users", String.class);
    assertTrue(hash.startsWith("$argon2id$"));
    assertFalse(hash.contains(PASSWORD));
    assertNull(result.getResponse().getCookie("refresh_token"));
  }

  @Test
  void duplicateAndUnknownFieldsRejected() throws Exception {
    register();
    assertEquals(
        409,
        postJson("/auth/register", Map.of("email", EMAIL, "password", PASSWORD))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        400,
        postJson(
                "/auth/register",
                Map.of("email", "other@example.com", "password", PASSWORD, "role", "admin"))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void passwordPolicyAndUnicodeCodePoints() throws Exception {
    assertEquals(
        400,
        postJson("/auth/register", Map.of("email", EMAIL, "password", "short"))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        201,
        postJson("/auth/register", Map.of("email", EMAIL, "password", "旅".repeat(15)))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void loginReturnsJwtAndProtectedIdentity() throws Exception {
    register();
    var r = login();
    assertEquals(200, r.getResponse().getStatus());
    assertEquals(900, data(r).get("expiresIn").asLong());
    assertTrue(cookie(r).isHttpOnly());
    assertEquals("/auth", cookie(r).getPath());
    assertEquals(200, me(access(r)));
    assertEquals(401, mvc.perform(get("/auth/me")).andReturn().getResponse().getStatus());
    assertEquals(401, me(access(r) + "x"));
    assertEquals("no-store", r.getResponse().getHeader("Cache-Control"));
  }

  @Test
  void missingAndWrongPasswordLookTheSame() throws Exception {
    register();
    var a =
        postJson("/auth/login", Map.of("email", EMAIL, "password", "wrong"))
            .andReturn()
            .getResponse();
    var b =
        postJson("/auth/login", Map.of("email", "absent@example.com", "password", "wrong"))
            .andReturn()
            .getResponse();
    assertEquals(401, a.getStatus());
    assertEquals(a.getContentAsString(), b.getContentAsString());
  }

  @Test
  void actualCsrfHandshakeRequired() throws Exception {
    var result = mvc.perform(get("/auth/csrf")).andReturn();
    String token = data(result).get("token").asText();
    Cookie csrfCookie = result.getResponse().getCookie("XSRF-TOKEN");
    assertNotNull(csrfCookie);
    var request =
        post("/auth/register")
            .header("Origin", ORIGIN)
            .header("X-Requested-With", "XMLHttpRequest")
            .header("X-XSRF-TOKEN", token)
            .cookie(csrfCookie)
            .contentType("application/json")
            .content(json.writeValueAsString(Map.of("email", EMAIL, "password", PASSWORD)));
    assertEquals(201, mvc.perform(request).andReturn().getResponse().getStatus());
    assertEquals(
        403,
        mvc.perform(
                post("/auth/logout")
                    .header("Origin", ORIGIN)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .contentType("application/json")
                    .content("{}"))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void invalidOriginHeadersMediaAndLargeBodies() throws Exception {
    assertEquals(
        403,
        mvc.perform(
                post("/auth/logout")
                    .header("Origin", "https://evil.test")
                    .header("X-Requested-With", "XMLHttpRequest")
                    .contentType("application/json")
                    .content("{}"))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        403,
        mvc.perform(
                post("/auth/logout")
                    .header("Origin", ORIGIN)
                    .contentType("application/json")
                    .content("{}"))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        415,
        mvc.perform(
                post("/auth/logout")
                    .header("Origin", ORIGIN)
                    .header("X-Requested-With", "XMLHttpRequest")
                    .contentType("text/plain")
                    .content("{}"))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(
        413,
        postJson("/auth/register", Map.of("email", "x".repeat(9000), "password", PASSWORD))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void refreshRotatesAndReplayCommitsRevocation() throws Exception {
    register();
    var initial = login();
    var next = postJson("/auth/refresh", Map.of(), cookie(initial)).andReturn();
    assertEquals(200, next.getResponse().getStatus());
    assertNotEquals(cookie(initial).getValue(), cookie(next).getValue());
    var replay = postJson("/auth/refresh", Map.of(), cookie(initial)).andReturn();
    assertEquals(401, replay.getResponse().getStatus());
    assertEquals(
        "REFRESH_TOKEN_REUSED",
        json.readTree(replay.getResponse().getContentAsString()).get("error").get("code").asText());
    assertEquals(401, me(access(next)));
    assertEquals(
        401,
        postJson("/auth/refresh", Map.of(), cookie(next)).andReturn().getResponse().getStatus());
  }

  @Test
  void logoutIsIdempotentAndDeviceScoped() throws Exception {
    register();
    var one = login();
    var two = login();
    assertEquals(
        200, postJson("/auth/logout", Map.of(), cookie(one)).andReturn().getResponse().getStatus());
    assertEquals(401, me(access(one)));
    assertEquals(200, me(access(two)));
    assertEquals(
        200, postJson("/auth/logout", Map.of(), cookie(one)).andReturn().getResponse().getStatus());
    assertEquals(200, postJson("/auth/logout", Map.of()).andReturn().getResponse().getStatus());
  }

  @Test
  void expiryAndAbsoluteSessionLimit() throws Exception {
    register();
    var initial = login();
    clock.advance(901);
    assertEquals(401, me(access(initial)));
    var next = postJson("/auth/refresh", Map.of(), cookie(initial)).andReturn();
    assertEquals(200, next.getResponse().getStatus());
    clock.advance(604800 - 901 - 30);
    var last = postJson("/auth/refresh", Map.of(), cookie(next)).andReturn();
    assertEquals(30, data(last).get("expiresIn").asLong());
    clock.advance(31);
    assertEquals(
        401,
        postJson("/auth/refresh", Map.of(), cookie(last)).andReturn().getResponse().getStatus());
  }

  @Test
  void resetRevokesAllDevicesAndIsSingleUse() throws Exception {
    register();
    var one = login();
    var two = login();
    String token = resetToken();
    assertEquals(
        200,
        postJson("/auth/reset-pwd", Map.of("token", token, "newPassword", "NewPassword2026!"))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(401, me(access(one)));
    assertEquals(401, me(access(two)));
    assertEquals(
        401,
        postJson("/auth/refresh", Map.of(), cookie(one)).andReturn().getResponse().getStatus());
    assertEquals(
        400,
        postJson("/auth/reset-pwd", Map.of("token", token, "newPassword", PASSWORD))
            .andReturn()
            .getResponse()
            .getStatus());
    assertEquals(401, login().getResponse().getStatus());
    assertEquals(
        200,
        postJson("/auth/login", Map.of("email", EMAIL, "password", "NewPassword2026!"))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void resetUnknownEmailAndExpiredToken() throws Exception {
    var unknown =
        postJson("/auth/request-reset-pwd", Map.of("email", "missing@example.com")).andReturn();
    register();
    var known = postJson("/auth/request-reset-pwd", Map.of("email", EMAIL)).andReturn();
    assertEquals(
        unknown.getResponse().getContentAsString(), known.getResponse().getContentAsString());
    worker.processOne();
    worker.processOne();
    verify(mail, times(1)).send(any(SimpleMailMessage.class));
    String token = resetToken();
    clock.advance(901);
    assertEquals(
        400,
        postJson("/auth/reset-pwd", Map.of("token", token, "newPassword", PASSWORD))
            .andReturn()
            .getResponse()
            .getStatus());
  }

  @Test
  void mailRetriesDurablyWithoutPlaintextToken() throws Exception {
    register();
    doThrow(new MailSendException("test smtp down")).when(mail).send(any(SimpleMailMessage.class));
    postJson("/auth/request-reset-pwd", Map.of("email", EMAIL));
    worker.processOne();
    assertEquals("ready", db.queryForObject("SELECT status FROM auth_mail_jobs", String.class));
    assertEquals(1, db.queryForObject("SELECT attempts FROM auth_mail_jobs", Integer.class));
    byte[] payload =
        db.queryForObject("SELECT encrypted_payload FROM auth_mail_jobs", byte[].class);
    assertNotNull(payload);
    clock.advance(61);
    doNothing().when(mail).send(any(SimpleMailMessage.class));
    worker.processOne();
    assertEquals("sent", db.queryForObject("SELECT status FROM auth_mail_jobs", String.class));
    assertNull(db.queryForObject("SELECT encrypted_payload FROM auth_mail_jobs", byte[].class));
  }

  @Test
  void rateLimitSurvivesFailedAuthentication() throws Exception {
    register();
    for (int i = 0; i < 5; i++)
      assertEquals(
          401,
          postJson("/auth/login", Map.of("email", EMAIL, "password", "wrong"))
              .andReturn()
              .getResponse()
              .getStatus());
    var r =
        postJson("/auth/login", Map.of("email", EMAIL, "password", "wrong"))
            .andReturn()
            .getResponse();
    assertEquals(429, r.getStatus());
    assertNotNull(r.getHeader("Retry-After"));
  }

  @Test
  void concurrentRefreshCannotLeaveReplaySessionActive() throws Exception {
    register();
    var initial = login();
    String token = cookie(initial).getValue();
    var pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Callable<Boolean> task =
          () -> {
            start.await();
            try {
              auth.rotate(token);
              return true;
            } catch (ApiError e) {
              return false;
            }
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      assertNotEquals(a.get(), b.get());
      assertEquals(401, me(access(initial)));
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void concurrentResetOnlyOneSucceeds() throws Exception {
    register();
    String token = resetToken();
    var pool = Executors.newFixedThreadPool(2);
    try {
      CountDownLatch start = new CountDownLatch(1);
      Callable<Boolean> task =
          () -> {
            start.await();
            try {
              auth.reset(token, "NewPassword2026!");
              return true;
            } catch (ApiError e) {
              return false;
            }
          };
      var a = pool.submit(task);
      var b = pool.submit(task);
      start.countDown();
      assertNotEquals(a.get(), b.get());
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void databaseOutageFailsClosed() throws Exception {
    register();
    var initial = login();
    db.execute("ALTER TABLE auth_sessions RENAME TO auth_sessions_temporarily_unavailable");
    try {
      assertEquals(503, me(access(initial)));
    } finally {
      db.execute("ALTER TABLE auth_sessions_temporarily_unavailable RENAME TO auth_sessions");
    }
  }

  @Test
  void ownershipRejectsAnotherUsersResource() {
    Long user = 1L;
    var identity = new TokenService.Identity(user, UUID.randomUUID());
    Ownership.requireOwner(identity, user);
    ApiError failure = assertThrows(ApiError.class, () -> Ownership.requireOwner(identity, 2L));
    assertEquals(403, failure.status);
  }

  @Test
  void numericUserIdentityMatchesTeamEntity() throws Exception {
    register();
    var loggedIn = login();
    long id = data(loggedIn).get("user").get("id").asLong();
    assertTrue(id > 0);
    assertTrue(data(loggedIn).get("user").get("id").isNumber());
    assertEquals(id, auth.authenticate(access(loggedIn)).userId());
    assertEquals(id, db.queryForObject("SELECT id FROM users WHERE email=?", Long.class, EMAIL));
  }

  @Test
  void crossOriginWishlistDeletePreflightIsAllowed() throws Exception {
    var response =
        mvc.perform(
                options("/wishlist/103")
                    .header("Origin", ORIGIN)
                    .header("Access-Control-Request-Method", "DELETE")
                    .header("Access-Control-Request-Headers", "Authorization"))
            .andReturn()
            .getResponse();
    assertEquals(200, response.getStatus());
    assertTrue(response.getHeader("Access-Control-Allow-Methods").contains("DELETE"));
  }
}
