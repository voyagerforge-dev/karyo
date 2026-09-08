CREATE TABLE IF NOT EXISTS storage_strategies (
    id                    BIGSERIAL PRIMARY KEY,
    version               INT NOT NULL DEFAULT 0,
    created               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id             BIGINT NOT NULL,
    name                  VARCHAR(100) NOT NULL,
    zone_id               BIGINT REFERENCES zones(id),
    mix_item              BOOLEAN NOT NULL DEFAULT TRUE,
    mix_client            BOOLEAN NOT NULL DEFAULT FALSE,
    near_picking_location BOOLEAN NOT NULL DEFAULT FALSE,
    sorts                 VARCHAR(255),
    UNIQUE(client_id, name)
);

CREATE INDEX idx_strategies_client ON storage_strategies(client_id);
