-- Auth-private tables only. Existing team entities and schema.sql are unchanged.
CREATE TABLE IF NOT EXISTS auth_sessions (
 id CHAR(36) PRIMARY KEY,
 user_id BIGINT NOT NULL,
 expires_at DATETIME(6) NOT NULL,
 revoked_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX auth_sessions_user_idx(user_id),
 FOREIGN KEY(user_id) REFERENCES users(id)
);
CREATE TABLE IF NOT EXISTS auth_refresh_tokens (
 id CHAR(36) PRIMARY KEY,
 session_id CHAR(36) NOT NULL,
 token_hash CHAR(64) NOT NULL UNIQUE,
 used BOOLEAN NOT NULL DEFAULT FALSE,
 expires_at DATETIME(6) NOT NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 active_session CHAR(36) GENERATED ALWAYS AS (CASE WHEN used=FALSE THEN session_id ELSE NULL END) STORED,
 UNIQUE KEY one_current_refresh(active_session),
 FOREIGN KEY(session_id) REFERENCES auth_sessions(id)
);
CREATE TABLE IF NOT EXISTS auth_mail_jobs (
 id CHAR(36) PRIMARY KEY,
 email VARCHAR(320) NOT NULL,
 status VARCHAR(20) NOT NULL DEFAULT 'pending',
 token_hash CHAR(64) NULL,
 encrypted_payload BLOB NULL,
 attempts INTEGER NOT NULL DEFAULT 0,
 next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 expires_at DATETIME(6) NULL,
 created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
 INDEX auth_mail_pending_idx(status,next_attempt_at),
 INDEX auth_mail_email_idx(email,created_at,id)
);
CREATE TABLE IF NOT EXISTS auth_rate_limits (
 bucket_key VARCHAR(128) PRIMARY KEY,
 count INTEGER NOT NULL,
 expires_at DATETIME(6) NOT NULL
);
CREATE TABLE IF NOT EXISTS auth_worker_lock (id INTEGER PRIMARY KEY);
INSERT IGNORE INTO auth_worker_lock(id) VALUES(1);
