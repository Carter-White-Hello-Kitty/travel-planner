CREATE DATABASE IF NOT EXISTS travel_planner;
USE travel_planner;
CREATE TABLE IF NOT EXISTS users
(
    id            BINARY(16) PRIMARY KEY,
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS pwd_reset_tokens
(
    id         BINARY(16) PRIMARY KEY,
    user_id    BINARY(16) NOT NULL REFERENCES users (id),
    token_hash CHAR(64)   NOT NULL UNIQUE,
    expires_at DATETIME   NOT NULL
);

CREATE TABLE IF NOT EXISTS pois
(
    id              BINARY(16) PRIMARY KEY,
    google_place_id VARCHAR(225) NOT NULL UNIQUE,
    name            VARCHAR(225) NOT NULL,
    address         TEXT,
    latitude        DOUBLE       NOT NULL,
    longitude       DOUBLE       NOT NULL,
    category        VARCHAR(100),
    rating          DOUBLE,
    CHECK (latitude BETWEEN -90 AND 90),
    CHECK (longitude BETWEEN -180 AND 180),
    CHECK (rating IS NULL OR rating BETWEEN 0 AND 5)
);

CREATE TABLE IF NOT EXISTS wishlist
(
    id         BINARY(16) PRIMARY KEY,
    user_id    BINARY(16) NOT NULL REFERENCES users (id),
    poi_id     BINARY(16) NOT NULL REFERENCES pois (id),
    created_at DATETIME   NOT NULL,
    CONSTRAINT uq_wishlist_user_poi UNIQUE (user_id, poi_id)
);

CREATE TABLE IF NOT EXISTS trips
(
    id        BINARY(16) PRIMARY KEY,
    user_id   BINARY(16)  NOT NULL REFERENCES users (id),
    status    VARCHAR(20) NOT NULL,
    day_count INTEGER     NOT NULL
);

CREATE TABLE IF NOT EXISTS trip_pois
(
    id         BINARY(16) PRIMARY KEY,
    trip_id    BINARY(16)  NOT NULL REFERENCES trips (id),
    poi_id     BINARY(16)  NOT NULL REFERENCES pois (id),
    day_number INTEGER     NOT NULL CHECK (day_number > 0),
    `sequence` INTEGER     NOT NULL CHECK (`sequence` > 0),
    status     VARCHAR(20) NOT NULL DEFAULT 'Planned',
    CONSTRAINT uq_trip_pois_day_sequence UNIQUE (trip_id, day_number, `sequence`)
);

CREATE TABLE IF NOT EXISTS trip_legs
(
    id               BINARY(16) PRIMARY KEY,
    from_trip_poi_id BINARY(16) NOT NULL REFERENCES trip_pois (id),
    to_trip_poi_id   BINARY(16) NOT NULL REFERENCES trip_pois (id),
    distance_meters  INTEGER    NOT NULL CHECK (distance_meters > 0),
    duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    transport_mode   VARCHAR(30)
);

CREATE TABLE IF NOT EXISTS travel_posts
(
    id      BINARY(16) PRIMARY KEY,
    trip_id BINARY(16) NOT NULL REFERENCES trips (id),
    content TEXT,
    photos  JSON
);
