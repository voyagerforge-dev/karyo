CREATE TABLE IF NOT EXISTS inventory_journals (
    id                      BIGSERIAL,
    created                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    version                 INT NOT NULL DEFAULT 0,
    client_id               BIGINT NOT NULL,
    from_unit_load          VARCHAR(255),
    to_unit_load            VARCHAR(255),
    from_storage_location   VARCHAR(255),
    to_storage_location     VARCHAR(255),
    activity_code           VARCHAR(50),
    product_number          VARCHAR(100),
    product_name            VARCHAR(255),
    lot_number              VARCHAR(255),
    serial_number           VARCHAR(255),
    record_type             INT NOT NULL,
    amount                  NUMERIC(17,4),
    stock_unit_amount       NUMERIC(17,4),
    operator_name           VARCHAR(100),
    correlation_id          VARCHAR(100),
    PRIMARY KEY (id, created)
) PARTITION BY RANGE (created);

CREATE TABLE inventory_journals_default PARTITION OF inventory_journals DEFAULT;

CREATE INDEX idx_journals_client ON inventory_journals(client_id);
CREATE INDEX idx_journals_product ON inventory_journals(product_number);
CREATE INDEX idx_journals_record_type ON inventory_journals(record_type);
CREATE INDEX idx_journals_correlation ON inventory_journals(correlation_id);
CREATE INDEX idx_journals_created ON inventory_journals(created);
