CREATE TABLE IF NOT EXISTS zones (
    id               BIGSERIAL PRIMARY KEY,
    version          INT NOT NULL DEFAULT 0,
    created          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name             VARCHAR(100) NOT NULL UNIQUE,
    description      VARCHAR(500),
    overflow_zone_id BIGINT REFERENCES zones(id)
);
