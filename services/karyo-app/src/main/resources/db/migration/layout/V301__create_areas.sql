CREATE TABLE IF NOT EXISTS areas (
    id          BIGSERIAL PRIMARY KEY,
    version     INT NOT NULL DEFAULT 0,
    created     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name        VARCHAR(100) NOT NULL UNIQUE,
    usages      VARCHAR(255)
);
