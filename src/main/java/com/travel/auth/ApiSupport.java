package com.travel.auth;

import jakarta.servlet.http.*;
import java.time.*;
import java.util.*;
import org.springframework.core.env.Environment;
import org.springframework.http.*;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class ApiSupport {
  final boolean secure;
  final Clock clock;
  private final JsonMapper json = JsonMapper.builder().build();

  public ApiSupport(Environment env, Clock c) {
    secure = env.getProperty("auth.cookie-secure", Boolean.class, true);
    clock = c;
  }

  public Map<String, Object> ok(String message, Object data) {
    Map<String, Object> b = new LinkedHashMap<>();
    b.put("message", message);
    b.put("data", data);
    return b;
  }

  public String cookie(String value, long age) {
    return ResponseCookie.from("refresh_token", value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/auth")
        .maxAge(Math.max(0, age))
        .build()
        .toString();
  }

  public String cookie(HttpServletRequest req) {
    if (req.getCookies() == null) return null;
    String found = null;
    for (Cookie c : req.getCookies())
      if (c.getName().equals("refresh_token")) {
        if (found != null) return null;
        found = c.getValue();
      }
    return found;
  }

  public void write(HttpServletResponse response, ApiError e) throws java.io.IOException {
    response.setStatus(e.status);
    response.setContentType("application/json");
    response.setCharacterEncoding("UTF-8");
    response.setHeader("Cache-Control", "no-store");
    if (e.clearCookie) response.addHeader("Set-Cookie", cookie("", 0));
    if (e.retryAfter > 0) response.setHeader("Retry-After", Integer.toString(e.retryAfter));
    response.getWriter().write(json.writeValueAsString(e.body()));
  }

  public void credentials(HttpServletResponse r, AuthService.Login login) {
    r.addHeader(
        "Set-Cookie",
        cookie(login.refresh(), Duration.between(clock.instant(), login.expiry()).toSeconds()));
  }
}
