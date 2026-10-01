-- V1: core schema for Book My Seat.
-- The database is the system of record: row locks + guarded updates decide
-- who gets each seat, CHECKs and the partial unique index make illegal
-- states unrepresentable even if application logic has a bug.

CREATE EXTENSION IF NOT EXISTS "pgcrypto";

-- ---------------------------------------------------------------- shows
CREATE TABLE shows (
    id             UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name           TEXT        NOT NULL CHECK (char_length(btrim(name)) > 0),
    price_paise    BIGINT      NOT NULL CHECK (price_paise > 0),
    per_user_limit INT         NOT NULL DEFAULT 4 CHECK (per_user_limit > 0),
    total_seats    INT         NOT NULL CHECK (total_seats >= 0),
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- -------------------------------------------------------- reservations
CREATE TABLE reservations (
    id           UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    show_id      UUID        NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    user_id      TEXT        NOT NULL CHECK (char_length(user_id) > 0),
    status       TEXT        NOT NULL DEFAULT 'confirmed'
                             CHECK (status IN ('confirmed', 'cancelled')),
    amount_paise BIGINT      NOT NULL CHECK (amount_paise >= 0),
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    cancelled_at TIMESTAMPTZ NULL,
    CHECK (
        (status = 'confirmed' AND cancelled_at IS NULL)
        OR (status = 'cancelled' AND cancelled_at IS NOT NULL)
    )
);
CREATE INDEX idx_reservations_user ON reservations (user_id);
CREATE INDEX idx_reservations_show ON reservations (show_id);

-- ---------------------------------------------------------------- seats
-- One row per physical seat. The contested resource on the hot path.
-- Reserve flow (G5) takes FOR UPDATE locks on these rows in sorted
-- seat_label order, then re-checks status before the guarded UPDATE.
CREATE TABLE seats (
    id             BIGSERIAL   PRIMARY KEY,
    show_id        UUID        NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    seat_label     TEXT        NOT NULL
                               CHECK (char_length(seat_label) > 0 AND char_length(seat_label) <= 32),
    status         TEXT        NOT NULL DEFAULT 'available'
                               CHECK (status IN ('available', 'held', 'confirmed')),
    reservation_id UUID        NULL REFERENCES reservations (id) ON DELETE SET NULL,
    user_id        TEXT        NULL,
    version        INT         NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (show_id, seat_label),
    CHECK (
        (status = 'available' AND reservation_id IS NULL AND user_id IS NULL)
        OR (status IN ('held', 'confirmed') AND reservation_id IS NOT NULL AND user_id IS NOT NULL)
    )
);
CREATE INDEX idx_seats_show_status ON seats (show_id, status);

-- --------------------------------------------------- reservation_seats
-- Safety net: even if the application forgot its guarded UPDATE, the
-- database refuses to hold the same seat twice while active.
CREATE TABLE reservation_seats (
    reservation_id UUID    NOT NULL REFERENCES reservations (id) ON DELETE CASCADE,
    show_id        UUID    NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    seat_label     TEXT    NOT NULL,
    active         BOOLEAN NOT NULL DEFAULT TRUE,
    PRIMARY KEY (reservation_id, show_id, seat_label)
);
CREATE UNIQUE INDEX uq_reservation_seats_active
    ON reservation_seats (show_id, seat_label) WHERE active;

-- ----------------------------------------------------- user_show_quota
-- Per-user limit counter (G6). Incremented conditionally inside the
-- reserve transaction so parallel requests from one user serialise here.
CREATE TABLE user_show_quota (
    user_id      TEXT NOT NULL CHECK (char_length(user_id) > 0),
    show_id      UUID NOT NULL REFERENCES shows (id) ON DELETE CASCADE,
    active_seats INT  NOT NULL DEFAULT 0 CHECK (active_seats >= 0),
    PRIMARY KEY (user_id, show_id)
);

-- ---------------------------------------------------- idempotency_keys
-- Exactly-once enforcement (G7). Written in the same transaction as the
-- reservation: a declined attempt rolls back and does not consume the key.
CREATE TABLE idempotency_keys (
    user_id         TEXT        NOT NULL,
    idempotency_key TEXT        NOT NULL
                                CHECK (char_length(idempotency_key) > 0
                                       AND char_length(idempotency_key) <= 128),
    request_hash    TEXT        NOT NULL,
    reservation_id  UUID        NULL REFERENCES reservations (id) ON DELETE CASCADE,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id, idempotency_key)
);
