CREATE TABLE IF NOT EXISTS inactive_products (
    id          BIGSERIAL PRIMARY KEY,
    item_data_id BIGINT NOT NULL,
    client_id   BIGINT NOT NULL,
    deactivated TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version     INT NOT NULL DEFAULT 0,
    created     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_inactive_products UNIQUE (item_data_id, client_id)
);

CREATE INDEX idx_inactive_products_client_item ON inactive_products (client_id, item_data_id);
