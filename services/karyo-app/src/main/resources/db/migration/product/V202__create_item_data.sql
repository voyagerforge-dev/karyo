CREATE TABLE IF NOT EXISTS item_data (
    id                          BIGSERIAL PRIMARY KEY,
    version                     INT NOT NULL DEFAULT 0,
    created                     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                    TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id                   BIGINT NOT NULL,
    number                      VARCHAR(100) NOT NULL,
    name                        VARCHAR(255) NOT NULL,
    description                 VARCHAR(2000),
    state                       INT NOT NULL DEFAULT 100,
    item_unit_id                BIGINT NOT NULL REFERENCES item_units(id),
    scale                       INT NOT NULL DEFAULT 0,
    weight                      NUMERIC(16,3),
    height                      NUMERIC(16,3),
    width                       NUMERIC(16,3),
    depth                       NUMERIC(16,3),
    lot_mandatory               BOOLEAN NOT NULL DEFAULT FALSE,
    best_before_mandatory       BOOLEAN NOT NULL DEFAULT FALSE,
    shelflife                   INT,
    serial_no_record_type       VARCHAR(30) NOT NULL DEFAULT 'NO_RECORD',
    default_unit_load_type_id   BIGINT,
    default_storage_strategy_id BIGINT,
    zone_id                     BIGINT,
    trade_group                 VARCHAR(100),
    image_url                   VARCHAR(500),
    UNIQUE(client_id, number)
);

CREATE INDEX idx_item_data_client ON item_data(client_id);
CREATE INDEX idx_item_data_number ON item_data(number);
CREATE INDEX idx_item_data_state ON item_data(state);
CREATE INDEX idx_item_data_trade_group ON item_data(trade_group);
