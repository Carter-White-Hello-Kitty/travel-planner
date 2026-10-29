package com.travel.auth;

import java.sql.Timestamp;
import java.time.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class RateLimiter {
  private final JdbcTemplate db;
  private final Secrets secrets;
  private final Clock clock;
  private final TransactionTemplate tx;

  public RateLimiter(JdbcTemplate db, Secrets secrets, Clock clock, TransactionTemplate tx) {
    this.db = db;
    this.secrets = secrets;
    this.clock = clock;
    this.tx = tx;
  }

  public void check(String key, int limit, int seconds) {
    long now = clock.instant().getEpochSecond(), window = now / seconds;
    int count =
        db
            .query(
                "SELECT count FROM auth_rate_limits WHERE bucket_key=?",
                (r, n) -> r.getInt(1),
                secrets.rateHash(key) + ":" + window)
            .stream()
            .findFirst()
            .orElse(0);
    if (count >= limit) throw exceeded((int) ((window + 1) * seconds - now));
  }

  public void hit(String key, int limit, int seconds) {
    long now = clock.instant().getEpochSecond(),
        window = now / seconds,
        end = (window + 1) * seconds;
    String bucket = secrets.rateHash(key) + ":" + window;
    Integer count =
        tx.execute(
            status -> {
              db.update(
                  "INSERT INTO auth_rate_limits(bucket_key,count,expires_at) VALUES (?,1,?) "
                      + "ON DUPLICATE KEY UPDATE count=count+1",
                  bucket,
                  Timestamp.from(Instant.ofEpochSecond(end)));
              return db.queryForObject(
                  "SELECT count FROM auth_rate_limits WHERE bucket_key=?", Integer.class, bucket);
            });
    if (count != null && count > limit) throw exceeded((int) (end - now));
  }

  private ApiError exceeded(int retryAfter) {
    ApiError error = new ApiError(429, "RATE_LIMITED", "请求过于频繁");
    error.retryAfter = retryAfter;
    return error;
  }
}
