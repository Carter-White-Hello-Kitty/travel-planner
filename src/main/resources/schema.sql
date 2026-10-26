CREATE TABLE IF NOT EXISTS public.users
(
    id            UUID PRIMARY KEY,
    email         VARCHAR(320) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL
);

CREATE TABLE IF NOT EXISTS public.pwd_reset_tokens
(
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES public.users (id),
    token_hash CHAR(64)    NOT NULL UNIQUE,
    expires_at TIMESTAMPTZ NOT NULL
);

CREATE TABLE IF NOT EXISTS public.pois
(
    id              UUID PRIMARY KEY,
    google_place_id VARCHAR(225)     NOT NULL UNIQUE,
    name            VARCHAR(225)     NOT NULL,
    address         TEXT,
    latitude        DOUBLE PRECISION NOT NULL,
    longitude       DOUBLE PRECISION NOT NULL,
    category        VARCHAR(100),
    rating          DOUBLE PRECISION
);

CREATE TABLE IF NOT EXISTS public.wishlist
(
    id         UUID PRIMARY KEY,
    user_id    UUID        NOT NULL REFERENCES public.users (id),
    poi_id     UUID        NOT NULL REFERENCES public.pois (id),
    created_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_wishlist_user_poi UNIQUE (user_id, poi_id)
);

CREATE TABLE IF NOT EXISTS public.trips
(
    id        UUID PRIMARY KEY,
    user_id   UUID        NOT NULL REFERENCES public.users (id),
    status    VARCHAR(20) NOT NULL,
    day_count INTEGER     NOT NULL
);

CREATE TABLE IF NOT EXISTS public.trip_pois
(
    id         UUID PRIMARY KEY,
    trip_id    UUID        NOT NULL REFERENCES public.trips (id),
    poi_id     UUID        NOT NULL REFERENCES public.pois (id),
    day_number INTEGER     NOT NULL CHECK (day_number > 0),
    sequence   INTEGER     NOT NULL CHECK (sequence > 0),
    status     VARCHAR(20) NOT NULL DEFAULT 'Planned',
    CONSTRAINT uq_trip_pois_day_sequence UNIQUE (trip_id, day_number, sequence)
);

CREATE TABLE IF NOT EXISTS public.trip_legs
(
    id               UUID PRIMARY KEY,
    from_trip_poi_id UUID    NOT NULL REFERENCES public.trip_pois (id),
    to_trip_poi_id   UUID    NOT NULL REFERENCES public.trip_pois (id),
    distance_meters  INTEGER NOT NULL CHECK (distance_meters > 0),
    duration_seconds INTEGER CHECK (duration_seconds IS NULL OR duration_seconds > 0),
    transport_mode   VARCHAR(30)
);

CREATE TABLE IF NOT EXISTS public.travel_posts
(
    id      UUID PRIMARY KEY,
    trip_id UUID NOT NULL REFERENCES public.trips (id),
    content TEXT,
    photos  JSONB
);
