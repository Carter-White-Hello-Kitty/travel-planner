USE travel_planner;

DROP TABLE IF EXISTS users;
DROP TABLE IF EXISTS pwd_reset_tokens;
DROP TABLE IF EXISTS pois;
DROP TABLE IF EXISTS wishlist;
DROP TABLE IF EXISTS trips;
DROP TABLE IF EXISTS trip_pois;
DROP TABLE IF EXISTS trip_legs;
DROP TABLE IF EXISTS travel_posts;

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
    name      VARCHAR(100) NOT NULL,
    status    VARCHAR(20) NOT NULL,
    day_count INTEGER     NOT NULL
);

CREATE TABLE IF NOT EXISTS trip_pois
(
    id         BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id    BIGINT      NOT NULL REFERENCES trips (id),
    poi_id     BIGINT      NOT NULL REFERENCES pois (id),
    day_number INTEGER     NOT NULL CHECK (day_number > 0),
    `sequence` INTEGER     NOT NULL CHECK (`sequence` > 0),
    status     VARCHAR(20) NOT NULL DEFAULT 'Planned',
    CONSTRAINT uq_trip_pois_day_sequence UNIQUE (trip_id, day_number, `sequence`)
);

CREATE TABLE IF NOT EXISTS trip_legs
(
    id               BIGINT PRIMARY KEY AUTO_INCREMENT,
    from_trip_poi_id BIGINT NOT NULL REFERENCES trip_pois (id),
    to_trip_poi_id   BIGINT NOT NULL REFERENCES trip_pois (id),
    distance_meters  INTEGER    NOT NULL CHECK (distance_meters > 0),
    duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    transport_mode   VARCHAR(30)
);

CREATE TABLE IF NOT EXISTS travel_posts
(
    id      BIGINT PRIMARY KEY AUTO_INCREMENT,
    trip_id BIGINT NOT NULL REFERENCES trips (id),
    content TEXT,
    photos  TEXT
);
