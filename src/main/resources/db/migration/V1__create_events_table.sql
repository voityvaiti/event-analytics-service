CREATE TABLE events (
    event_id     TEXT        PRIMARY KEY,
    source       TEXT        NOT NULL,
    user_id      TEXT        NOT NULL,
    event_type   TEXT        NOT NULL,
    occurred_at  TIMESTAMPTZ NOT NULL,
    properties   JSONB       NOT NULL DEFAULT '{}'::JSONB,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
