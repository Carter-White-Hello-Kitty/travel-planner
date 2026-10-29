package com.travel.auth;

import java.time.Instant;
import java.util.UUID;

/** Business contract used by HTTP/security callers; persistence stays in the implementation. */
public interface AuthService {
  record User(Long id, String email, String hash) {}

  record Login(User user, TokenService.Access access, String refresh, Instant expiry) {}

  User register(String email, String password);

  Login login(String email, String password, String oldCookie);

  Login rotate(String refreshToken);

  void logout(String refreshToken);

  void requestReset(String email);

  void reset(String resetToken, String newPassword);

  TokenService.Identity authenticate(String accessToken);

  UUID refreshSessionId(String refreshToken);
}
