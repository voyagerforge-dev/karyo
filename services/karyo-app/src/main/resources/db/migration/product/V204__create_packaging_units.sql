CREATE TABLE IF NOT EXISTS packaging_units (
    id              BIGSERIAL PRIMARY KEY,
    version         INT NOT NULL DEFAULT 0,
    created         TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    name            VARCHAR(100) NOT NULL,
    item_data_id    BIGINT NOT NULL REFERENCES item_data(id) ON DELETE CASCADE,
    amount          NUMERIC(17,4) NOT NULL DEFAULT 1,
    item_unit_id    BIGINT REFERENCES item_units(id),
    height          NUMERIC(16,3),
    width           NUMERIC(16,3),
    depth           NUMERIC(16,3),
    weight          NUMERIC(16,3)
);

CREATE INDEX idx_packaging_units_item ON packaging_units(item_data_id);
