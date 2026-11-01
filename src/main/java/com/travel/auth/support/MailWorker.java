package com.travel.auth.support;

import com.travel.auth.config.Secrets;
import com.travel.auth.constant.AuthConstants;
import com.travel.auth.entity.AuthMailJob;
import com.travel.auth.entity.MailJobStatus;
import com.travel.auth.entity.PwdResetToken;
import com.travel.auth.entity.User;
import com.travel.auth.repository.AuthMailJobRepository;
import com.travel.auth.repository.AuthRateLimitRepository;
import com.travel.auth.repository.AuthRefreshTokenRepository;
import com.travel.auth.repository.AuthSessionRepository;
import com.travel.auth.repository.AuthWorkerLockRepository;
import com.travel.auth.repository.PwdResetTokenRepository;
import com.travel.auth.repository.UserRepository;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MailWorker {
  private static final ZoneOffset UTC = ZoneOffset.UTC;
  private static final List<MailJobStatus> PENDING_READY =
      List.of(MailJobStatus.PENDING, MailJobStatus.READY);
  private static final List<MailJobStatus> FINISHED =
      List.of(MailJobStatus.SENT, MailJobStatus.FAILED);

  private final AuthMailJobRepository mailJobRepository;
  private final AuthWorkerLockRepository workerLockRepository;
  private final UserRepository userRepository;
  private final PwdResetTokenRepository pwdResetTokenRepository;
  private final AuthRefreshTokenRepository refreshTokenRepository;
  private final AuthSessionRepository sessionRepository;
  private final AuthRateLimitRepository rateLimitRepository;
  private final Secrets secrets;
  private final Clock clock;
  private final JavaMailSender mail;
  private final TransactionTemplate tx;
  private final String frontend;
  private final String from;
  private final boolean enabled;
  private final Logger log = LoggerFactory.getLogger(MailWorker.class);

  public MailWorker(
      AuthMailJobRepository mailJobRepository,
      AuthWorkerLockRepository workerLockRepository,
      UserRepository userRepository,
      PwdResetTokenRepository pwdResetTokenRepository,
      AuthRefreshTokenRepository refreshTokenRepository,
      AuthSessionRepository sessionRepository,
      AuthRateLimitRepository rateLimitRepository,
      Secrets secrets,
      Clock clock,
      JavaMailSender mail,
      TransactionTemplate tx,
      Environment env) {
    this.mailJobRepository = mailJobRepository;
    this.workerLockRepository = workerLockRepository;
    this.userRepository = userRepository;
    this.pwdResetTokenRepository = pwdResetTokenRepository;
    this.refreshTokenRepository = refreshTokenRepository;
    this.sessionRepository = sessionRepository;
    this.rateLimitRepository = rateLimitRepository;
    this.secrets = secrets;
    this.clock = clock;
    this.mail = mail;
    this.tx = tx;
    this.frontend = env.getProperty("auth.frontend-url");
    this.from = env.getProperty("auth.mail-from");
    this.enabled = env.getProperty("auth.worker-enabled", Boolean.class, true);
    if (frontend == null
        || !(frontend.startsWith("https://") || frontend.startsWith("http://localhost:")))
      throw new IllegalArgumentException("Configure a trusted FRONTEND_URL");
  }

  private LocalDateTime now() {
    return LocalDateTime.ofInstant(clock.instant(), UTC);
  }

  @Scheduled(fixedDelay = AuthConstants.WORKER_TICK_INTERVAL_MS)
  public void tick() {
    if (!enabled) return;
    try {
      processOne();
    } catch (Exception error) {
      log.warn("Reset worker deferred ({})", error.getClass().getSimpleName());
    }
  }

  public void processOne() {
    tx.executeWithoutResult(
        status -> {
          // Transaction-scoped MySQL row lock coordinates multiple worker instances.
          workerLockRepository.lock();
          List<AuthMailJob> eligible = mailJobRepository.findEligible(PENDING_READY, now());
          Map<String, AuthMailJob> oldestByEmail = new LinkedHashMap<>();
          for (AuthMailJob candidate : eligible) {
            oldestByEmail.putIfAbsent(candidate.getEmail(), candidate);
          }
          if (oldestByEmail.isEmpty()) return;
          AuthMailJob job = oldestByEmail.values().iterator().next();

          String hash = job.getTokenHash();
          byte[] payload = job.getEncryptedPayload();
          LocalDateTime expiry = job.getExpiresAt();

          if (job.getStatus() == MailJobStatus.PENDING) {
            User user = userRepository.findByEmail(job.getEmail()).orElse(null);
            if (user == null) {
              finish(job, MailJobStatus.SENT);
              return;
            }
            userRepository.findByIdForUpdate(user.getId());
            String token = secrets.token();
            hash = secrets.hash(token);
            expiry = now().plusSeconds(AuthConstants.RESET_TOKEN_TTL_SECONDS);
            pwdResetTokenRepository.deleteByUserId(user.getId());
            PwdResetToken resetToken = new PwdResetToken();
            resetToken.setUser(user);
            resetToken.setTokenHash(hash);
            resetToken.setExpiresAt(expiry);
            pwdResetTokenRepository.save(resetToken);
            payload =
                secrets.encrypt(frontend.replaceAll("/$", "") + "/reset-password#token=" + token);
            job.setStatus(MailJobStatus.READY);
            job.setTokenHash(hash);
            job.setEncryptedPayload(payload);
            job.setExpiresAt(expiry);
          }

          if (expiry == null || !expiry.isAfter(now())) {
            fail(job, hash);
            return;
          }

          try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(job.getEmail());
            message.setSubject("Travel Planner Password Reset");
            message.setText(
                "Please open the following link within 15 minutes to set a new password. "
                    + "If you did not request this, please ignore it.\n"
                    + secrets.decrypt(payload));
            mail.send(message);
            finish(job, MailJobStatus.SENT);
          } catch (Exception error) {
            if (job.getAttempts() >= AuthConstants.MAIL_MAX_ATTEMPTS) {
              fail(job, hash);
              return;
            }
            long backoffSeconds = AuthConstants.MAIL_BACKOFF_BASE_SECONDS << job.getAttempts();
            job.setNextAttemptAt(now().plusSeconds(backoffSeconds));
            job.setAttempts(job.getAttempts() + 1);
          }
        });
  }

  private void finish(AuthMailJob job, MailJobStatus status) {
    job.setStatus(status);
    job.setEncryptedPayload(null);
  }

  private void fail(AuthMailJob job, String hash) {
    if (hash != null) pwdResetTokenRepository.deleteByTokenHash(hash);
    finish(job, MailJobStatus.FAILED);
  }

  @Scheduled(fixedDelay = AuthConstants.CLEANUP_INTERVAL_MS)
  public void cleanup() {
    if (!enabled) return;
    try {
      tx.executeWithoutResult(
          status -> {
            pwdResetTokenRepository.deleteByExpiresAtBefore(now());
            refreshTokenRepository.deleteBySessionExpiredBefore(
                now().minusSeconds(AuthConstants.CLEANUP_GRACE_SECONDS));
            sessionRepository.deleteByExpiresAtBefore(
                now().minusSeconds(AuthConstants.CLEANUP_GRACE_SECONDS));
            rateLimitRepository.deleteByExpiresAtBefore(now());
            mailJobRepository.deleteByStatusInAndCreatedAtBefore(
                FINISHED, now().minusSeconds(AuthConstants.MAIL_JOB_RETENTION_SECONDS));
          });
    } catch (Exception error) {
      log.warn("Auth cleanup deferred ({})", error.getClass().getSimpleName());
    }
  }
}
