CREATE TABLE IF NOT EXISTS unit_load_types (
    id                  BIGSERIAL PRIMARY KEY,
    version             INT NOT NULL DEFAULT 0,
    created             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name                VARCHAR(100) NOT NULL UNIQUE,
    height              NUMERIC(16,3),
    width               NUMERIC(16,3),
    depth               NUMERIC(16,3),
    lifting_capacity    NUMERIC(16,3),
    weight              NUMERIC(16,3),
    usages              VARCHAR(255),
    aggregate_stocks    BOOLEAN NOT NULL DEFAULT FALSE
);
