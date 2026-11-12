CREATE TABLE IF NOT EXISTS users
(
    id            BIGINT PRIMARY KEY AUTO_INCREMENT,
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS pwd_reset_tokens
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    BIGINT NOT NULL REFERENCES users (id),
    token_hash CHAR(64)   NOT NULL UNIQUE,
    expires_at DATETIME   NOT NULL
);

CREATE TABLE IF NOT EXISTS wishlist
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id         BIGINT NOT NULL REFERENCES users (id),
    google_place_id VARCHAR(225) NOT NULL,
    created_at      DATETIME NOT NULL,
    CONSTRAINT uq_wishlist_user_google_place UNIQUE (user_id, google_place_id)
);

CREATE TABLE IF NOT EXISTS trips
(
    id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id   BIGINT      NOT NULL REFERENCES users (id),
    name      VARCHAR(255) NOT NULL,
    status    VARCHAR(20) NOT NULL,
    day_count INTEGER     NOT NULL,
    start_date DATE NULL
);

-- Existing local volumes may still have the removed revision column.
SET @trip_revision_exists = (SELECT COUNT(*) FROM information_schema.columns
    WHERE table_schema = DATABASE() AND table_name = 'trips' AND column_name = 'revision');
SET @trip_revision_sql = IF(@trip_revision_exists = 1,
    'ALTER TABLE trips DROP COLUMN revision', 'SELECT 1');
PREPARE trip_revision_stmt FROM @trip_revision_sql;
EXECUTE trip_revision_stmt;
DEALLOCATE PREPARE trip_revision_stmt;

CREATE TABLE IF NOT EXISTS trip_days
(
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL REFERENCES trips (id),
    day_number INTEGER NULL
);

CREATE TABLE IF NOT EXISTS trip_pois
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_day_id     BIGINT NOT NULL REFERENCES trip_days (id),
    google_place_id VARCHAR(225) NOT NULL,
    sequence        INTEGER NOT NULL CHECK (sequence > 0),
    status          VARCHAR(20) NOT NULL DEFAULT 'Planned'
);

CREATE TABLE IF NOT EXISTS trip_generation_requests
(
    id           BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id      BIGINT NOT NULL,
    request_id   CHAR(36) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    trip_id      BIGINT NOT NULL,
    created_at   DATETIME(6) NOT NULL,
    CONSTRAINT uq_trip_generation_user_request UNIQUE (user_id, request_id),
    FOREIGN KEY (user_id) REFERENCES users (id),
    FOREIGN KEY (trip_id) REFERENCES trips (id)
);

CREATE TABLE IF NOT EXISTS travel_posts
(
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL REFERENCES trips (id),
    content TEXT,
    photos  TEXT
);

-- Authentication & authorization tables
CREATE TABLE IF NOT EXISTS auth_sessions
(
    id         CHAR(36) PRIMARY KEY,
    user_id    BIGINT NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX auth_sessions_user_idx (user_id),
    FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE TABLE IF NOT EXISTS auth_refresh_tokens
(
    id             CHAR(36) PRIMARY KEY,
    session_id     CHAR(36) NOT NULL,
    token_hash     CHAR(64) NOT NULL UNIQUE,
    used           BOOLEAN NOT NULL DEFAULT FALSE,
    expires_at     DATETIME(6) NOT NULL,
    created_at     DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    active_session CHAR(36) GENERATED ALWAYS AS (CASE WHEN used = FALSE THEN session_id ELSE NULL END) STORED,
    UNIQUE KEY one_current_refresh (active_session),
    FOREIGN KEY (session_id) REFERENCES auth_sessions (id)
);

CREATE TABLE IF NOT EXISTS auth_mail_jobs
(
    id                CHAR(36) PRIMARY KEY,
    email             VARCHAR(320) NOT NULL,
    status            VARCHAR(20) NOT NULL DEFAULT 'pending',
    token_hash        CHAR(64) NULL,
    encrypted_payload BLOB NULL,
    attempts          INTEGER NOT NULL DEFAULT 0,
    next_attempt_at   DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    expires_at        DATETIME(6) NULL,
    created_at        DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    INDEX auth_mail_pending_idx (status, next_attempt_at),
    INDEX auth_mail_email_idx (email, created_at, id)
);

CREATE TABLE IF NOT EXISTS auth_rate_limits
(
    bucket_key VARCHAR(128) PRIMARY KEY,
    count      INTEGER NOT NULL,
    expires_at DATETIME(6) NOT NULL
);

CREATE TABLE IF NOT EXISTS auth_worker_lock
(
    id INTEGER PRIMARY KEY
);

INSERT IGNORE INTO auth_worker_lock (id) VALUES (1);

-- Sida Xue — Travel Journal Search：新增持久化索引任务表，支持失败重试。
-- Durable indexing jobs, independent of the publishing module's entity model.
CREATE TABLE IF NOT EXISTS journal_search_jobs
(
    post_id BIGINT PRIMARY KEY,
    revision BIGINT NOT NULL DEFAULT 1
);
