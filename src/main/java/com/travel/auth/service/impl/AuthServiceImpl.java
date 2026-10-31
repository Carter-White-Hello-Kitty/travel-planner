package com.travel.auth.service.impl;

import com.travel.auth.config.Secrets;
import com.travel.auth.constant.AuthConstants;
import com.travel.auth.entity.AuthMailJob;
import com.travel.auth.entity.AuthRefreshToken;
import com.travel.auth.entity.AuthSession;
import com.travel.auth.entity.PwdResetToken;
import com.travel.auth.repository.AuthMailJobRepository;
import com.travel.auth.repository.AuthRefreshTokenRepository;
import com.travel.auth.repository.AuthSessionRepository;
import com.travel.auth.repository.PwdResetTokenRepository;
import com.travel.auth.repository.UserRepository;
import com.travel.auth.service.AuthService;
import com.travel.auth.service.TokenService;
import com.travel.common.ApiError;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class AuthServiceImpl implements AuthService {
  private static final ZoneOffset UTC = ZoneOffset.UTC;

  private final UserRepository userRepository;
  private final AuthSessionRepository sessionRepository;
  private final AuthRefreshTokenRepository refreshTokenRepository;
  private final PwdResetTokenRepository pwdResetTokenRepository;
  private final AuthMailJobRepository mailJobRepository;
  private final Secrets secrets;
  private final TokenService jwt;
  private final PasswordEncoder passwords;
  private final Clock clock;
  private final TransactionTemplate tx;
  private final String dummy;

  public AuthServiceImpl(
      UserRepository userRepository,
      AuthSessionRepository sessionRepository,
      AuthRefreshTokenRepository refreshTokenRepository,
      PwdResetTokenRepository pwdResetTokenRepository,
      AuthMailJobRepository mailJobRepository,
      Secrets secrets,
      TokenService jwt,
      PasswordEncoder passwords,
      Clock clock,
      TransactionTemplate tx) {
    this.userRepository = userRepository;
    this.sessionRepository = sessionRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.pwdResetTokenRepository = pwdResetTokenRepository;
    this.mailJobRepository = mailJobRepository;
    this.secrets = secrets;
    this.jwt = jwt;
    this.passwords = passwords;
    this.clock = clock;
    this.tx = tx;
    this.dummy = passwords.encode(secrets.token());
  }

  public record Session(UUID id, Long user, Instant expiry, boolean revoked) {}

  public record Refresh(UUID session, String hash, boolean used) {}

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), UTC);
  }

  private AuthService.User user(String email) {
    return userRepository
        .findByEmail(email)
        .map(u -> new AuthService.User(u.getId(), u.getEmail(), u.getPasswordHash()))
        .orElse(null);
  }

  private AuthService.User lockUser(Long id) {
    return userRepository
        .findByIdForUpdate(id)
        .map(u -> new AuthService.User(u.getId(), u.getEmail(), u.getPasswordHash()))
        .orElse(null);
  }

  private Refresh refresh(String token) {
    if (token == null || !token.matches(AuthConstants.TOKEN_PATTERN)) return null;
    return refreshTokenRepository
        .findByTokenHash(secrets.hash(token))
        .map(t -> new Refresh(UUID.fromString(t.getSessionId()), t.getTokenHash(), t.isUsed()))
        .orElse(null);
  }

  private Session session(UUID id, boolean lock) {
    Optional<AuthSession> found =
        lock ? sessionRepository.findByIdForUpdate(id.toString()) : sessionRepository.findById(id.toString());
    return found
        .map(
            s ->
                new Session(
                    UUID.fromString(s.getId()),
                    s.getUserId(),
                    s.getExpiresAt().toInstant(UTC),
                    s.getRevokedAt() != null))
        .orElse(null);
  }

  private boolean active(Session s) {
    return s != null && !s.revoked() && s.expiry().isAfter(clock.instant());
  }

  private void revoke(UUID sid) {
    sessionRepository
        .findByIdForUpdate(sid.toString())
        .ifPresent(
            s -> {
              if (s.getRevokedAt() == null) {
                s.setRevokedAt(now());
              }
            });
  }

  public AuthService.User register(String email, String password) {
    String encoded = passwords.encode(password);
    com.travel.auth.entity.User entity = new com.travel.auth.entity.User();
    entity.setEmail(email);
    entity.setPasswordHash(encoded);
    try {
      userRepository.save(entity);
    } catch (DataIntegrityViolationException e) {
      throw new ApiError(409, "EMAIL_ALREADY_REGISTERED", "邮箱已注册");
    }
    return new AuthService.User(entity.getId(), entity.getEmail(), entity.getPasswordHash());
  }

  public Login login(String email, String password, String old) {
    AuthService.User candidate = user(email);
    if (candidate == null) {
      passwords.matches(password, dummy);
      throw credentials();
    }
    if (!passwords.matches(password, candidate.hash())) throw credentials();
    if (old != null) logout(old);
    return tx.execute(
        st -> {
          AuthService.User current = lockUser(candidate.id());
          if (current == null || !passwords.matches(password, current.hash())) throw credentials();
          UUID sid = UUID.randomUUID();
          Instant expiry = clock.instant().plusSeconds(AuthConstants.SESSION_TTL_SECONDS);
          String token = secrets.token();
          AuthSession session = new AuthSession();
          session.setId(sid.toString());
          session.setUserId(current.id());
          session.setExpiresAt(LocalDateTime.ofInstant(expiry, UTC));
          sessionRepository.save(session);
          saveRefresh(sid, token, expiry);
          return new Login(current, jwt.issue(current.id(), sid, expiry), token, expiry);
        });
  }

  private ApiError credentials() {
    return new ApiError(401, "INVALID_CREDENTIALS", "邮箱或密码错误");
  }

  private void saveRefresh(UUID sid, String token, Instant expiry) {
    AuthRefreshToken refresh = new AuthRefreshToken();
    refresh.setId(UUID.randomUUID().toString());
    refresh.setSessionId(sid.toString());
    refresh.setTokenHash(secrets.hash(token));
    refresh.setExpiresAt(LocalDateTime.ofInstant(expiry, UTC));
    refreshTokenRepository.save(refresh);
  }

  public Login rotate(String token) {
    Refresh initial = refresh(token);
    Session initialSession = initial == null ? null : session(initial.session(), false);
    if (initialSession == null) throw invalidRefresh();
    Object result =
        tx.execute(
            st -> {
              lockUser(initialSession.user());
              Session current = session(initialSession.id(), true);
              Refresh latest = refresh(token);
              if (!active(current) || latest == null) return invalidRefresh();
              if (latest.used()) {
                revoke(current.id());
                return new ApiError(401, "REFRESH_TOKEN_REUSED", "刷新令牌已被使用").clearing();
              }
              TokenService.Access access;
              try {
                access = jwt.issue(current.user(), current.id(), current.expiry());
              } catch (ApiError e) {
                return e;
              }
              refreshTokenRepository.findByTokenHash(latest.hash()).ifPresent(t -> t.setUsed(true));
              refreshTokenRepository.flush();
              String next = secrets.token();
              saveRefresh(current.id(), next, current.expiry());
              return new Login(null, access, next, current.expiry());
            });
    if (result instanceof ApiError e) throw e;
    return (Login) result;
  }

  private ApiError invalidRefresh() {
    return new ApiError(401, "REFRESH_TOKEN_INVALID_OR_EXPIRED", "刷新令牌无效或过期").clearing();
  }

  public void logout(String token) {
    Refresh r = refresh(token);
    Session s = r == null ? null : session(r.session(), false);
    if (s == null) return;
    tx.executeWithoutResult(
        st -> {
          lockUser(s.user());
          Session current = session(s.id(), true);
          if (current != null) revoke(s.id());
        });
  }

  public void requestReset(String email) {
    AuthMailJob job = new AuthMailJob();
    job.setId(UUID.randomUUID().toString());
    job.setEmail(email);
    job.setStatus("pending");
    job.setAttempts(0);
    job.setNextAttemptAt(now());
    mailJobRepository.save(job);
  }

  public void reset(String token, String password) {
    String hash = secrets.hash(token);
    String encoded = passwords.encode(password);
    PwdResetToken found = pwdResetTokenRepository.findByTokenHash(hash).orElse(null);
    if (found == null) throw resetError();
    Long userId = found.getUser().getId();
    tx.executeWithoutResult(
        st -> {
          userRepository.findByIdForUpdate(userId).orElseThrow(this::resetError);
          List<PwdResetToken> tokens =
              pwdResetTokenRepository.findValidByTokenHashForUpdate(hash, now());
          if (tokens.isEmpty()) throw resetError();
          com.travel.auth.entity.User user = userRepository.findById(userId).orElseThrow();
          user.setPasswordHash(encoded);
          pwdResetTokenRepository.deleteByUserId(userId);
          sessionRepository.revokeAllByUserId(userId, now());
        });
  }

  private ApiError resetError() {
    return new ApiError(400, "RESET_TOKEN_INVALID_OR_EXPIRED", "链接无效或已过期，请重新申请");
  }

  public TokenService.Identity authenticate(String token) {
    TokenService.Identity id = jwt.verify(token);
    Session s = session(id.sessionId(), false);
    if (!active(s) || !s.user().equals(id.userId()))
      throw new ApiError(401, "SESSION_REVOKED", "会话已失效，请重新登录");
    return id;
  }

  public UUID refreshSessionId(String token) {
    Refresh current = refresh(token);
    return current == null ? null : current.session();
  }
}
