package com.travel.auth;

import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.slf4j.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.*;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class MailWorker {
  final JdbcTemplate db;
  final TransactionTemplate tx;
  final AuthUserStore users;
  final Secrets secrets;
  final Clock clock;
  final JavaMailSender mail;
  final String frontend, from;
  final boolean enabled;
  final Logger log = LoggerFactory.getLogger(MailWorker.class);

  record Job(
      String id,
      String email,
      String status,
      String hash,
      byte[] payload,
      int attempts,
      Instant expiry) {}

  public MailWorker(
      JdbcTemplate db,
      TransactionTemplate tx,
      AuthUserStore users,
      Secrets secrets,
      Clock clock,
      JavaMailSender mail,
      Environment env) {
    this.db = db;
    this.tx = tx;
    this.users = users;
    this.secrets = secrets;
    this.clock = clock;
    this.mail = mail;
    frontend = env.getProperty("auth.frontend-url");
    from = env.getProperty("auth.mail-from");
    enabled = env.getProperty("auth.worker-enabled", Boolean.class, true);
    if (frontend == null
        || !(frontend.startsWith("https://") || frontend.startsWith("http://localhost:")))
      throw new IllegalArgumentException("Configure a trusted FRONTEND_URL");
  }

  @Scheduled(fixedDelay = 1000)
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
          // Transaction-scoped MySQL row lock replaces PostgreSQL advisory locks.
          db.queryForObject("SELECT id FROM auth_worker_lock WHERE id=1 FOR UPDATE", Integer.class);
          List<Job> jobs =
              db.query(
                  "SELECT * FROM auth_mail_jobs j WHERE status IN ('pending','ready') AND"
                      + " next_attempt_at<=? AND NOT EXISTS (SELECT 1 FROM auth_mail_jobs older"
                      + " WHERE older.email=j.email AND older.status IN ('pending','ready') AND"
                      + " (older.created_at,older.id)<(j.created_at,j.id)) ORDER BY created_at,id"
                      + " LIMIT 1 FOR UPDATE",
                  (r, n) ->
                      new Job(
                          r.getString("id"),
                          r.getString("email"),
                          r.getString("status"),
                          r.getString("token_hash"),
                          r.getBytes("encrypted_payload"),
                          r.getInt("attempts"),
                          r.getTimestamp("expires_at") == null
                              ? null
                              : r.getTimestamp("expires_at").toInstant()),
                  Timestamp.from(clock.instant()));
          if (jobs.isEmpty()) return;
          Job job = jobs.get(0);
          String hash = job.hash;
          byte[] payload = job.payload;
          Instant expiry = job.expiry;
          if (job.status.equals("pending")) {
            AuthService.User user = users.findByEmail(job.email);
            if (user == null) {
              finish(job.id, "sent");
              return;
            }
            users.lockById(user.id());
            String token = secrets.token();
            hash = secrets.hash(token);
            expiry = clock.instant().plusSeconds(900);
            db.update("DELETE FROM pwd_reset_tokens WHERE user_id=?", user.id());
            db.update(
                "INSERT INTO pwd_reset_tokens(user_id,token_hash,expires_at) VALUES (?,?,?)",
                user.id(),
                hash,
                Timestamp.from(expiry));
            payload =
                secrets.encrypt(frontend.replaceAll("/$", "") + "/reset-password#token=" + token);
            db.update(
                "UPDATE auth_mail_jobs SET"
                    + " status='ready',token_hash=?,encrypted_payload=?,expires_at=? WHERE id=?",
                hash,
                payload,
                Timestamp.from(expiry),
                job.id);
          }
          if (expiry == null || !expiry.isAfter(clock.instant())) {
            fail(job.id, hash);
            return;
          }
          try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(from);
            message.setTo(job.email);
            message.setSubject("Travel Planner 密码重置");
            message.setText("请在15分钟内打开以下链接设置新密码。若非本人申请，请忽略。\n" + secrets.decrypt(payload));
            mail.send(message);
            finish(job.id, "sent");
          } catch (Exception error) {
            if (job.attempts >= 3) {
              fail(job.id, hash);
              return;
            }
            db.update(
                "UPDATE auth_mail_jobs SET attempts=attempts+1,next_attempt_at=? WHERE id=?",
                Timestamp.from(clock.instant().plusSeconds(60L << job.attempts)),
                job.id);
          }
        });
  }

  void finish(String id, String status) {
    db.update("UPDATE auth_mail_jobs SET status=?,encrypted_payload=NULL WHERE id=?", status, id);
  }

  void fail(String id, String hash) {
    if (hash != null) db.update("DELETE FROM pwd_reset_tokens WHERE token_hash=?", hash);
    finish(id, "failed");
  }

  @Scheduled(fixedDelay = 3600000)
  public void cleanup() {
    if (!enabled) return;
    try {
      tx.executeWithoutResult(
          status -> {
            db.update(
                "DELETE FROM pwd_reset_tokens WHERE expires_at<?", Timestamp.from(clock.instant()));
            db.update(
                "DELETE rt FROM auth_refresh_tokens rt JOIN auth_sessions s ON s.id=rt.session_id"
                    + " WHERE s.expires_at<?",
                Timestamp.from(clock.instant().minusSeconds(900)));
            db.update(
                "DELETE FROM auth_sessions WHERE expires_at<?",
                Timestamp.from(clock.instant().minusSeconds(900)));
            db.update(
                "DELETE FROM auth_rate_limits WHERE expires_at<?", Timestamp.from(clock.instant()));
            db.update(
                "DELETE FROM auth_mail_jobs WHERE status IN ('sent','failed') AND created_at<?",
                Timestamp.from(clock.instant().minusSeconds(86400)));
          });
    } catch (Exception error) {
      log.warn("Auth cleanup deferred ({})", error.getClass().getSimpleName());
    }
  }
}
