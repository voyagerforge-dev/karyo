CREATE TABLE IF NOT EXISTS item_substitutions (
    id                      BIGSERIAL PRIMARY KEY,
    version                 INT NOT NULL DEFAULT 0,
    created                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id               BIGINT NOT NULL,
    item_data_id            BIGINT NOT NULL,
    substitute_item_data_id BIGINT NOT NULL,
    priority                INT NOT NULL DEFAULT 1,
    active                  BOOLEAN NOT NULL DEFAULT TRUE,
    UNIQUE(client_id, item_data_id, substitute_item_data_id)
);

CREATE INDEX idx_substitutions_client ON item_substitutions(client_id);
CREATE INDEX idx_substitutions_primary ON item_substitutions(item_data_id, active, priority);
