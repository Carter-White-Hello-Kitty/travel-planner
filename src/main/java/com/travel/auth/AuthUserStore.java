package com.travel.auth;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Shared user lookup/locking for authentication transactions and reset-mail processing. */
@Repository
public class AuthUserStore {
  private final JdbcTemplate db;

  public AuthUserStore(JdbcTemplate db) {
    this.db = db;
  }

  public AuthService.User findByEmail(String email) {
    return db
        .query(
            "SELECT * FROM users WHERE email=?",
            (r, n) ->
                new AuthService.User(
                    r.getLong("id"), r.getString("email"), r.getString("password_hash")),
            email)
        .stream()
        .findFirst()
        .orElse(null);
  }

  public AuthService.User lockById(Long id) {
    return db
        .query(
            "SELECT * FROM users WHERE id=? FOR UPDATE",
            (r, n) -> new AuthService.User(id, r.getString("email"), r.getString("password_hash")),
            id)
        .stream()
        .findFirst()
        .orElse(null);
  }
}
