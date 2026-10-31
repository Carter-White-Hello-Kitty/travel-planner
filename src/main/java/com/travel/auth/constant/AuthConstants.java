package com.travel.auth.constant;

import java.util.List;

/** Authentication module constants. Tunable values may later move to application.yaml. */
public final class AuthConstants {
  private AuthConstants() {}

  // Public endpoints (no Bearer token required)
  public static final String CSRF_PATH = "/auth/csrf";
  public static final List<String> PUBLIC_POST_PATHS =
      List.of(
          "/auth/register",
          "/auth/login",
          "/auth/request-reset-pwd",
          "/auth/reset-pwd",
          "/auth/logout",
          "/auth/refresh");

  // Token & session lifetimes (seconds)
  public static final long SESSION_TTL_SECONDS = 604800; // 7 days
  public static final long ACCESS_TOKEN_TTL_SECONDS = 900; // 15 minutes
  public static final long RESET_TOKEN_TTL_SECONDS = 900; // 15 minutes
  public static final long CLOCK_SKEW_SECONDS = 30;

  // Input constraints
  public static final int EMAIL_MAX_LENGTH = 320;
  public static final int PASSWORD_MIN_LENGTH = 15;
  public static final int PASSWORD_MAX_LENGTH = 128;
  public static final int REQUEST_BODY_MAX_BYTES = 8192;

  // Patterns & names
  public static final String TOKEN_PATTERN = "[A-Za-z0-9_-]{43}";
  public static final String EMAIL_PATTERN = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$";
  public static final String REFRESH_COOKIE_NAME = "refresh_token";

  // Argon2 password hashing
  public static final int ARGON2_SALT_LENGTH = 16;
  public static final int ARGON2_HASH_LENGTH = 32;
  public static final int ARGON2_PARALLELISM = 1;
  public static final int ARGON2_MEMORY_KB = 19456;
  public static final int ARGON2_ITERATIONS = 2;

  // Mail worker
  public static final int MAIL_MAX_ATTEMPTS = 3;
  public static final long MAIL_BACKOFF_BASE_SECONDS = 60;
  public static final long MAIL_JOB_RETENTION_SECONDS = 86400;
  public static final long WORKER_TICK_INTERVAL_MS = 1000;
  public static final long CLEANUP_INTERVAL_MS = 3600000;
  public static final long CLEANUP_GRACE_SECONDS = 900;

  // Rate limits (limit, window in seconds)
  public static final int REGISTER_LIMIT = 10;
  public static final int REGISTER_WINDOW = 3600;
  public static final int LOGIN_IP_LIMIT = 30;
  public static final int LOGIN_IP_WINDOW = 900;
  public static final int LOGIN_FAILED_LIMIT = 5;
  public static final int LOGIN_FAILED_WINDOW = 900;
  public static final int RESET_REQUEST_IP_LIMIT = 10;
  public static final int RESET_REQUEST_IP_WINDOW = 3600;
  public static final int RESET_EMAIL_LIMIT = 3;
  public static final int RESET_EMAIL_WINDOW = 3600;
  public static final int RESET_IP_LIMIT = 10;
  public static final int RESET_IP_WINDOW = 900;
  public static final int LOGOUT_LIMIT = 60;
  public static final int LOGOUT_WINDOW = 60;
  public static final int REFRESH_IP_LIMIT = 120;
  public static final int REFRESH_IP_WINDOW = 60;
  public static final int REFRESH_SESSION_LIMIT = 30;
  public static final int REFRESH_SESSION_WINDOW = 60;
}
