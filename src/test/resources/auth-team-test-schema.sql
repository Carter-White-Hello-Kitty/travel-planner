

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

CREATE TABLE IF NOT EXISTS pois
(
    id              BIGINT PRIMARY KEY AUTO_INCREMENT,
    google_place_id VARCHAR(225) NOT NULL UNIQUE
);

CREATE TABLE IF NOT EXISTS wishlist
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id    BIGINT NOT NULL REFERENCES users (id),
    poi_id     BIGINT NOT NULL REFERENCES pois (id),
    created_at DATETIME   NOT NULL,
    CONSTRAINT uq_wishlist_user_poi UNIQUE (user_id, poi_id)
);

CREATE TABLE IF NOT EXISTS trips
(
    id        BIGINT PRIMARY KEY AUTO_INCREMENT,
    user_id   BIGINT      NOT NULL REFERENCES users (id),
    name      VARCHAR(255) NOT NULL,
    status    VARCHAR(20) NOT NULL,
    day_count INTEGER     NOT NULL
);

CREATE TABLE IF NOT EXISTS trip_days
(
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL REFERENCES trips (id)
);

CREATE TABLE IF NOT EXISTS trip_pois
(
    id          BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_day_id BIGINT      NOT NULL REFERENCES trip_days (id),
    poi_id      BIGINT      NOT NULL REFERENCES pois (id),
    sequence  INTEGER     NOT NULL CHECK (sequence > 0),
    status      VARCHAR(20) NOT NULL DEFAULT 'Planned'
);

CREATE TABLE IF NOT EXISTS travel_posts
(
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL REFERENCES trips (id),
    content TEXT,
    photos  TEXT
);
