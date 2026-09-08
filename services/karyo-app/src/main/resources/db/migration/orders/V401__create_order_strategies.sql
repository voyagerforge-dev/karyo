CREATE TABLE IF NOT EXISTS order_strategies (
    id                    BIGSERIAL PRIMARY KEY,
    version               INT NOT NULL DEFAULT 0,
    created               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name                  VARCHAR(100) NOT NULL UNIQUE,
    use_locked_stock      BOOLEAN NOT NULL DEFAULT FALSE,
    prefer_complete       BOOLEAN NOT NULL DEFAULT TRUE,
    extension_properties  JSONB NOT NULL DEFAULT '{}'::jsonb
);
