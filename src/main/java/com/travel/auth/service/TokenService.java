package com.travel.auth.service;

import java.time.Instant;
import java.util.UUID;

public interface TokenService {

  record Identity(Long userId, UUID sessionId) {}

  record Access(String value, long seconds) {}

  Access issue(Long user, UUID sid, Instant expiry);

  Identity verify(String token);
}
