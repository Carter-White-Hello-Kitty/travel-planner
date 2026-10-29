package com.travel.auth;

import jakarta.servlet.http.*;
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
  private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");

  public AuthController(AuthService a, RateLimiter l, ApiSupport p) {
    auth = a;
    limits = l;
    api = p;
  }

  @GetMapping("/csrf")
  public Object csrf(CsrfToken csrf) {
    return api.ok(
        "CSRF token", Map.of("headerName", csrf.getHeaderName(), "token", csrf.getToken()));
  }

  @GetMapping("/me")
  public Object me(@AuthenticationPrincipal TokenService.Identity id) {
    return api.ok("当前用户", Map.of("userId", id.userId()));
  }

  @PostMapping("/register")
  @ResponseStatus(HttpStatus.CREATED)
  public Object register(@RequestBody Map<String, Object> b, HttpServletRequest q) {
    limits.hit("register:" + q.getRemoteAddr(), 10, 3600);
    keys(b, "email", "password");
    return api.ok(
        "账户创建成功，请登录",
        Map.of("user", auth.view(auth.register(email(b), password(b, "password", true)))));
  }

  @PostMapping("/login")
  public Object login(
      @RequestBody Map<String, Object> b, HttpServletRequest q, HttpServletResponse r) {
    limits.hit("login-ip:" + q.getRemoteAddr(), 30, 900);
    keys(b, "email", "password");
    String email = email(b), password = password(b, "password", false);
    limits.check("login-failed:" + email + ":" + q.getRemoteAddr(), 5, 900);
    try {
      // Failed attempts are counted separately and persist outside the authentication transaction.
      AuthService.Login result = auth.login(email, password, api.cookie(q));
      api.credentials(r, result);
      return api.ok(
          "登录成功",
          Map.of(
              "user",
              auth.view(result.user()),
              "accessToken",
              result.access().value(),
              "tokenType",
              "Bearer",
              "expiresIn",
              result.access().seconds()));
    } catch (ApiError e) {
      if (e.code.equals("INVALID_CREDENTIALS"))
        limits.hit("login-failed:" + email + ":" + q.getRemoteAddr(), 5, 900);
      throw e;
    }
  }

  @PostMapping("/request-reset-pwd")
  @ResponseStatus(HttpStatus.ACCEPTED)
  public Object requestReset(@RequestBody Map<String, Object> b, HttpServletRequest q) {
    limits.hit("reset-request-ip:" + q.getRemoteAddr(), 10, 3600);
    keys(b, "email");
    String email = email(b);
    limits.hit("reset-email:" + email, 3, 3600);
    auth.requestReset(email);
    return api.ok("如果该邮箱已注册，我们将发送密码重置链接，请检查邮箱", null);
  }

  @PostMapping("/reset-pwd")
  public Object reset(
      @RequestBody Map<String, Object> b, HttpServletRequest q, HttpServletResponse r) {
    limits.hit("reset-ip:" + q.getRemoteAddr(), 10, 900);
    keys(b, "token", "newPassword");
    String token = string(b, "token");
    if (!token.matches("[A-Za-z0-9_-]{43}")) throw ApiError.invalid("token", "令牌格式无效");
    auth.reset(token, password(b, "newPassword", true));
    r.addHeader("Set-Cookie", api.cookie("", 0));
    return api.ok("密码已重置，请重新登录", null);
  }

  @PostMapping("/logout")
  public Object logout(
      @RequestBody Map<String, Object> b, HttpServletRequest q, HttpServletResponse r) {
    limits.hit("logout:" + q.getRemoteAddr(), 60, 60);
    keys(b);
    auth.logout(api.cookie(q));
    r.addHeader("Set-Cookie", api.cookie("", 0));
    return api.ok("已退出登录", null);
  }

  @PostMapping("/refresh")
  public Object refresh(
      @RequestBody Map<String, Object> b, HttpServletRequest q, HttpServletResponse r) {
    limits.hit("refresh-ip:" + q.getRemoteAddr(), 120, 60);
    keys(b);
    String token = api.cookie(q);
    AuthService.Refresh rt = auth.refresh(token);
    if (rt != null) limits.hit("refresh-sid:" + rt.session(), 30, 60);
    AuthService.Login result = auth.rotate(token);
    api.credentials(r, result);
    return api.ok(
        "令牌已刷新",
        Map.of(
            "accessToken",
            result.access().value(),
            "tokenType",
            "Bearer",
            "expiresIn",
            result.access().seconds()));
  }

  void keys(Map<String, Object> b, String... allowed) {
    Set<String> names = Set.of(allowed);
    for (String k : b.keySet()) if (!names.contains(k)) throw ApiError.invalid(k, "未知字段");
  }

  String string(Map<String, Object> b, String field) {
    if (!(b.get(field) instanceof String s)) throw ApiError.invalid(field, "必填字符串");
    return s;
  }

  String email(Map<String, Object> b) {
    String s = string(b, "email").strip().toLowerCase(Locale.ROOT);
    if (s.length() > 320 || !EMAIL.matcher(s).matches()) throw ApiError.invalid("email", "邮箱格式无效");
    return s;
  }

  String password(Map<String, Object> b, String field, boolean newPassword) {
    String s = string(b, field);
    int n = s.codePointCount(0, s.length());
    if (n < (newPassword ? 15 : 1) || n > 128) throw ApiError.invalid(field, "密码长度无效");
    return s;
  }
}
