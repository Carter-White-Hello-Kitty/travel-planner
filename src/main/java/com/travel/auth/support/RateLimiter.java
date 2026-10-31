package com.travel.auth.support;

import com.travel.auth.config.Secrets;
import com.travel.auth.repository.AuthRateLimitRepository;
import com.travel.common.ApiError;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RateLimiter {
  private static final ZoneOffset UTC = ZoneOffset.UTC;

  private final AuthRateLimitRepository rateLimitRepository;
  private final Secrets secrets;
  private final Clock clock;
  private final TransactionTemplate tx;

  public RateLimiter(
      AuthRateLimitRepository rateLimitRepository,
      Secrets secrets,
      Clock clock,
      TransactionTemplate tx) {
    this.rateLimitRepository = rateLimitRepository;
    this.secrets = secrets;
    this.clock = clock;
    this.tx = tx;
  }

  public void check(String key, int limit, int seconds) {
    long now = clock.instant().getEpochSecond();
    long window = now / seconds;
    String bucket = secrets.rateHash(key) + ":" + window;
    int count = rateLimitRepository.findById(bucket).map(r -> r.getCount()).orElse(0);
    if (count >= limit) throw exceeded((int) ((window + 1) * seconds - now));
  }

  public void hit(String key, int limit, int seconds) {
    long now = clock.instant().getEpochSecond();
    long window = now / seconds;
    long end = (window + 1) * seconds;
    String bucket = secrets.rateHash(key) + ":" + window;
    tx.executeWithoutResult(
        status ->
            rateLimitRepository.increment(
                bucket, LocalDateTime.ofInstant(Instant.ofEpochSecond(end), UTC)));
    int count = rateLimitRepository.findById(bucket).map(r -> r.getCount()).orElse(0);
    if (count > limit) throw exceeded((int) (end - now));
  }

  private ApiError exceeded(int retryAfter) {
    ApiError error = new ApiError(429, "RATE_LIMITED", "请求过于频繁");
    error.retryAfter = retryAfter;
    return error;
  }
}
