CREATE TABLE IF NOT EXISTS stock_units (
    id                  BIGSERIAL PRIMARY KEY,
    version             INT NOT NULL DEFAULT 0,
    created             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id           BIGINT NOT NULL,
    item_data_id        BIGINT NOT NULL,
    item_data_number    VARCHAR(100) NOT NULL,
    amount              NUMERIC(17,4) NOT NULL DEFAULT 0,
    reserved_amount     NUMERIC(17,4) NOT NULL DEFAULT 0,
    serial_number       VARCHAR(255),
    lot_number          VARCHAR(255),
    best_before         DATE,
    state               INT NOT NULL DEFAULT 0,
    lock_type           INT NOT NULL DEFAULT 0,
    strategy_date       TIMESTAMPTZ,
    unit_load_id        BIGINT NOT NULL REFERENCES unit_loads(id),
    packaging_unit_id   BIGINT,
    activity_code       VARCHAR(50),

    CONSTRAINT chk_amount_non_negative CHECK (amount >= 0),
    CONSTRAINT chk_reserved_non_negative CHECK (reserved_amount >= 0),
    CONSTRAINT chk_reserved_not_exceeding CHECK (reserved_amount <= amount)
);

CREATE INDEX idx_stock_units_client ON stock_units(client_id);
CREATE INDEX idx_stock_units_item_data ON stock_units(item_data_id);
CREATE INDEX idx_stock_units_item_number ON stock_units(item_data_number);
CREATE INDEX idx_stock_units_state ON stock_units(state);
CREATE INDEX idx_stock_units_lock ON stock_units(lock_type);
CREATE INDEX idx_stock_units_unit_load ON stock_units(unit_load_id);
CREATE INDEX idx_stock_units_lot ON stock_units(lot_number);
CREATE INDEX idx_stock_units_selection ON stock_units(item_data_id, state, lock_type, client_id);
CREATE INDEX idx_stock_units_fifo ON stock_units(strategy_date, amount, created, id);
