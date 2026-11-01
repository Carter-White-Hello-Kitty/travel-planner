package com.travel.auth.controller;

import com.travel.auth.constant.AuthConstants;
import com.travel.auth.entity.User;
import com.travel.auth.service.AuthService;
import com.travel.auth.support.RateLimiter;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import com.travel.common.ApiSupport;
import com.travel.common.Result;
import jakarta.servlet.http.*;
import java.time.Clock;
import java.time.Duration;
import java.util.*;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/auth")
public class AuthController {
  private final AuthService auth;
  private final RateLimiter limits;
  private final ApiSupport api;
  private final Clock clock;
  private static final Pattern EMAIL = Pattern.compile(AuthConstants.EMAIL_PATTERN);

  public AuthController(AuthService auth, RateLimiter limits, ApiSupport api, Clock clock) {
    this.auth = auth;
    this.limits = limits;
    this.api = api;
    this.clock = clock;
  }

  @GetMapping("/csrf")
  public Result<?> csrf(CsrfToken csrf) {
    return api.ok(
        "CSRF token", Map.of("headerName", csrf.getHeaderName(), "token", csrf.getToken()));
  }

  @GetMapping("/me")
  public Result<?> me(@AuthenticationPrincipal TokenService.Identity id) {
    return api.ok("Current user", Map.of("userId", id.userId()));
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Result<?> register(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    limits.hit(
        "register:" + request.getRemoteAddr(), AuthConstants.REGISTER_LIMIT, AuthConstants.REGISTER_WINDOW);
    keys(body, "email", "password");
    return api.ok(
        "Account created successfully. Please log in.",
        Map.of("user", userView(auth.register(email(body), password(body, "password", true)))));
  }

  @PostMapping("/login")
  public Result<?> login(
      @RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
    limits.hit(
        "login-ip:" + request.getRemoteAddr(), AuthConstants.LOGIN_IP_LIMIT, AuthConstants.LOGIN_IP_WINDOW);
    keys(body, "email", "password");
    String email = email(body), password = password(body, "password", false);
    limits.check(
        "login-failed:" + email + ":" + request.getRemoteAddr(),
        AuthConstants.LOGIN_FAILED_LIMIT,
        AuthConstants.LOGIN_FAILED_WINDOW);
    try {
      AuthService.Login result = auth.login(email, password, api.cookie(request));
      credentials(response, result);
      return api.ok(
          "Login successful",
          Map.of(
              "user",
              userView(result.user()),
              "accessToken",
              result.access().value(),
              "tokenType",
              "Bearer",
              "expiresIn",
              result.access().seconds()));
    } catch (ApiError e) {
      if (e.code.equals("INVALID_CREDENTIALS"))
        limits.hit(
            "login-failed:" + email + ":" + request.getRemoteAddr(),
            AuthConstants.LOGIN_FAILED_LIMIT,
            AuthConstants.LOGIN_FAILED_WINDOW);
      throw e;
    }
  }

  @PostMapping("/request-reset-pwd")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Result<?> requestReset(@RequestBody Map<String, Object> body, HttpServletRequest request) {
    limits.hit(
        "reset-request-ip:" + request.getRemoteAddr(),
        AuthConstants.RESET_REQUEST_IP_LIMIT,
        AuthConstants.RESET_REQUEST_IP_WINDOW);
    keys(body, "email");
    String email = email(body);
    limits.hit("reset-email:" + email, AuthConstants.RESET_EMAIL_LIMIT, AuthConstants.RESET_EMAIL_WINDOW);
    auth.requestReset(email);
    return api.ok("If this email is registered, a password reset link has been sent. Please check your inbox.", null);
  }

  @PostMapping("/reset-pwd")
  public Result<?> reset(
      @RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
    limits.hit(
        "reset-ip:" + request.getRemoteAddr(), AuthConstants.RESET_IP_LIMIT, AuthConstants.RESET_IP_WINDOW);
    keys(body, "token", "newPassword");
    String token = string(body, "token");
    if (!token.matches(AuthConstants.TOKEN_PATTERN)) throw ApiError.invalid("token", "Invalid token format");
    auth.reset(token, password(body, "newPassword", true));
    response.addHeader("Set-Cookie", api.cookie("", 0));
    return api.ok("Password has been reset. Please log in again.", null);
  }

  @PostMapping("/logout")
  public Result<?> logout(
      @RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
    limits.hit(
        "logout:" + request.getRemoteAddr(), AuthConstants.LOGOUT_LIMIT, AuthConstants.LOGOUT_WINDOW);
    keys(body);
    auth.logout(api.cookie(request));
    response.addHeader("Set-Cookie", api.cookie("", 0));
    return api.ok("Logged out successfully", null);
  }

  @PostMapping("/refresh")
  public Result<?> refresh(
      @RequestBody Map<String, Object> body, HttpServletRequest request, HttpServletResponse response) {
    limits.hit(
        "refresh-ip:" + request.getRemoteAddr(),
        AuthConstants.REFRESH_IP_LIMIT,
        AuthConstants.REFRESH_IP_WINDOW);
    keys(body);
    String token = api.cookie(request);
    UUID sessionId = auth.refreshSessionId(token);
    if (sessionId != null)
      limits.hit(
          "refresh-sid:" + sessionId,
          AuthConstants.REFRESH_SESSION_LIMIT,
          AuthConstants.REFRESH_SESSION_WINDOW);
    AuthService.Login result = auth.rotate(token);
    credentials(response, result);
    return api.ok(
        "Token refreshed",
        Map.of(
            "accessToken",
            result.access().value(),
            "tokenType",
            "Bearer",
            "expiresIn",
            result.access().seconds()));
  }

  private Map<String, Object> userView(User user) {
    return Map.of("id", user.getId(), "email", user.getEmail());
  }

  private void credentials(HttpServletResponse response, AuthService.Login login) {
    response.addHeader(
        "Set-Cookie",
        api.cookie(
            login.refresh(), Duration.between(clock.instant(), login.expiry()).toSeconds()));
  }

  void keys(Map<String, Object> body, String... allowed) {
    Set<String> names = Set.of(allowed);
    for (String k : body.keySet()) if (!names.contains(k)) throw ApiError.invalid(k, "Unknown field");
  }

  String string(Map<String, Object> body, String field) {
    if (!(body.get(field) instanceof String s)) throw ApiError.invalid(field, "Required string");
    return s;
  }

  String email(Map<String, Object> body) {
    String s = string(body, "email").strip().toLowerCase(Locale.ROOT);
    if (s.length() > AuthConstants.EMAIL_MAX_LENGTH || !EMAIL.matcher(s).matches())
      throw ApiError.invalid("email", "Invalid email format");
    return s;
  }

  String password(Map<String, Object> body, String field, boolean newPassword) {
    String s = string(body, field);
    int n = s.codePointCount(0, s.length());
    if (n < (newPassword ? AuthConstants.PASSWORD_MIN_LENGTH : 1) || n > AuthConstants.PASSWORD_MAX_LENGTH)
      throw ApiError.invalid(field, "Invalid password length");
    return s;
  }
}
