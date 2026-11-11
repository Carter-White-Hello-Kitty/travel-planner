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
    destination VARCHAR(255) NULL,
    start_date DATE NULL
);

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

-- Additive, repeatable upgrade for installations created before destination support.
SET @destination_ddl = IF(
    (SELECT COUNT(*) FROM information_schema.COLUMNS
     WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'trips' AND COLUMN_NAME = 'destination') = 0,
    'ALTER TABLE trips ADD COLUMN destination VARCHAR(255) NULL', 'SELECT 1');
PREPARE destination_upgrade FROM @destination_ddl;
EXECUTE destination_upgrade;
DEALLOCATE PREPARE destination_upgrade;
