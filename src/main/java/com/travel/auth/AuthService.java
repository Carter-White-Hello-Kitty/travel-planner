package com.travel.auth;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthService {
  final JdbcTemplate db;
  final Secrets secrets;
  final TokenService jwt;
  final PasswordEncoder passwords;
  final Clock clock;
  final TransactionTemplate tx;
  final String dummy;

  public AuthService(
      JdbcTemplate d,
      Secrets s,
      TokenService j,
      PasswordEncoder p,
      Clock c,
      TransactionTemplate t) {
    db = d;
    secrets = s;
    jwt = j;
    passwords = p;
    clock = c;
    tx = t;
    dummy = p.encode(s.token());
  }

  public record User(Long id, String email, String hash) {}

  public record Session(UUID id, Long user, Instant expiry, boolean revoked) {}

  public record Refresh(UUID session, String hash, boolean used) {}

  public record Login(User user, TokenService.Access access, String refresh, Instant expiry) {}

  User user(String email) {
    return db
        .query(
            "SELECT * FROM users WHERE email=?",
            (r, n) -> new User(r.getLong("id"), r.getString("email"), r.getString("password_hash")),
            email)
        .stream()
        .findFirst()
        .orElse(null);
  }

  User lockUser(Long id) {
    return db
        .query(
            "SELECT * FROM users WHERE id=? FOR UPDATE",
            (r, n) -> new User(id, r.getString("email"), r.getString("password_hash")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }

  Refresh refresh(String token) {
    if (token == null || !token.matches("[A-Za-z0-9_-]{43}")) return null;
    return db
        .query(
            "SELECT session_id,token_hash,used FROM auth_refresh_tokens WHERE token_hash=?",
            (r, n) -> new Refresh(UUID.fromString(r.getString(1)), r.getString(2), r.getBoolean(3)),
            secrets.hash(token))
        .stream()
        .findFirst()
        .orElse(null);
  }

  Session session(UUID id, boolean lock) {
    return db
        .query(
            "SELECT * FROM auth_sessions WHERE id=?" + (lock ? " FOR UPDATE" : ""),
            (r, n) ->
                new Session(
                    id,
                    r.getLong("user_id"),
                    r.getTimestamp("expires_at").toInstant(),
                    r.getTimestamp("revoked_at") != null),
            id.toString())
        .stream()
        .findFirst()
        .orElse(null);
  }

  boolean active(Session s) {
    return s != null && !s.revoked && s.expiry.isAfter(clock.instant());
  }

  void revoke(UUID sid) {
    db.update(
        "UPDATE auth_sessions SET revoked_at=? WHERE id=? AND revoked_at IS NULL",
        Timestamp.from(clock.instant()),
        sid.toString());
  }

  public User register(String email, String password) {
    String encoded = passwords.encode(password);
    var keys = new org.springframework.jdbc.support.GeneratedKeyHolder();
    try {
      db.update(
          connection -> {
            var statement =
                connection.prepareStatement(
                    "INSERT INTO users(email,password_hash) VALUES (?,?)",
                    java.sql.Statement.RETURN_GENERATED_KEYS);
            statement.setString(1, email);
            statement.setString(2, encoded);
            return statement;
          },
          keys);
    } catch (DuplicateKeyException e) {
      throw new ApiError(409, "EMAIL_ALREADY_REGISTERED", "邮箱已注册");
    }
    return new User(Objects.requireNonNull(keys.getKey()).longValue(), email, encoded);
  }

  public Login login(String email, String password, String old) {
    User candidate = user(email);
    if (candidate == null) {
      passwords.matches(password, dummy);
      throw credentials();
    }
    // Revoke an old browser session separately to avoid taking two different user locks in
    // conflicting order.
    // Passwords must be verified before any old-cookie effect.
    if (!passwords.matches(password, candidate.hash)) throw credentials();
    if (old != null) logout(old);
    return tx.execute(
        st -> {
          User current = lockUser(candidate.id);
          if (current == null || !passwords.matches(password, current.hash)) throw credentials();
          UUID sid = UUID.randomUUID();
          Instant expiry = clock.instant().plusSeconds(604800);
          String token = secrets.token();
          db.update(
              "INSERT INTO auth_sessions(id,user_id,expires_at) VALUES (?,?,?)",
              sid.toString(),
              current.id,
              Timestamp.from(expiry));
          saveRefresh(sid, token, expiry);
          return new Login(current, jwt.issue(current.id, sid, expiry), token, expiry);
        });
  }

  private ApiError credentials() {
    return new ApiError(401, "INVALID_CREDENTIALS", "邮箱或密码错误");
  }

  void saveRefresh(UUID sid, String token, Instant expiry) {
    db.update(
        "INSERT INTO auth_refresh_tokens(id,session_id,token_hash,expires_at) VALUES (?,?,?,?)",
        UUID.randomUUID().toString(),
        sid.toString(),
        secrets.hash(token),
        Timestamp.from(expiry));
  }

  public Login rotate(String token) {
    Refresh r = refresh(token);
    Session initial = r == null ? null : session(r.session, false);
    if (initial == null) throw invalidRefresh();
    // Return errors from the transaction so replay revocation is COMMITTED, not rolled back.
    Object result =
        tx.execute(
            st -> {
              lockUser(initial.user);
              Session current = session(initial.id, true);
              Refresh latest = refresh(token);
              if (!active(current) || latest == null) return invalidRefresh();
              if (latest.used) {
                revoke(current.id);
                return new ApiError(401, "REFRESH_TOKEN_REUSED", "刷新令牌已被使用").clearing();
              }
              TokenService.Access access;
              try {
                access = jwt.issue(current.user, current.id, current.expiry);
              } catch (ApiError e) {
                return e;
              }
              db.update("UPDATE auth_refresh_tokens SET used=TRUE WHERE token_hash=?", latest.hash);
              String next = secrets.token();
              saveRefresh(current.id, next, current.expiry);
              return new Login(null, access, next, current.expiry);
            });
    if (result instanceof ApiError e) throw e;
    return (Login) result;
  }

  private ApiError invalidRefresh() {
    return new ApiError(401, "REFRESH_TOKEN_INVALID_OR_EXPIRED", "刷新令牌无效或过期").clearing();
  }

  public void logout(String token) {
    Refresh r = refresh(token);
    Session s = r == null ? null : session(r.session, false);
    if (s == null) return;
    tx.executeWithoutResult(
        st -> {
          lockUser(s.user);
          Session current = session(s.id, true);
          if (current != null) revoke(s.id);
        });
  }

  public void requestReset(String email) {
    db.update(
        "INSERT INTO auth_mail_jobs(id,email,next_attempt_at,created_at) VALUES (?,?,?,?)",
        UUID.randomUUID().toString(),
        email,
        Timestamp.from(clock.instant()),
        Timestamp.from(clock.instant()));
  }

  public void reset(String token, String password) {
    String hash = secrets.hash(token), encoded = passwords.encode(password);
    Long user =
        db
            .query(
                "SELECT user_id FROM pwd_reset_tokens WHERE token_hash=?",
                (r, n) -> r.getLong(1),
                hash)
            .stream()
            .findFirst()
            .orElse(null);
    if (user == null) throw resetError();
    tx.executeWithoutResult(
        st -> {
          lockUser(user);
          List<Long> ids =
              db.query(
                  "SELECT id FROM pwd_reset_tokens WHERE token_hash=? AND expires_at>? FOR UPDATE",
                  (r, n) -> r.getLong(1),
                  hash,
                  Timestamp.from(clock.instant()));
          if (ids.isEmpty()) throw resetError();
          db.update("UPDATE users SET password_hash=? WHERE id=?", encoded, user);
          db.update("DELETE FROM pwd_reset_tokens WHERE user_id=?", user);
          db.update(
              "UPDATE auth_sessions SET revoked_at=? WHERE user_id=? AND revoked_at IS NULL",
              Timestamp.from(clock.instant()),
              user);
        });
  }

  private ApiError resetError() {
    return new ApiError(400, "RESET_TOKEN_INVALID_OR_EXPIRED", "链接无效或已过期，请重新申请");
  }

  public TokenService.Identity authenticate(String token) {
    TokenService.Identity id = jwt.verify(token);
    Session s = session(id.sessionId(), false);
    if (!active(s) || !s.user.equals(id.userId()))
      throw new ApiError(401, "SESSION_REVOKED", "会话已失效，请重新登录");
    return id;
  }

  public Map<String, Object> view(User u) {
    return Map.of("id", u.id, "email", u.email);
  }
}
