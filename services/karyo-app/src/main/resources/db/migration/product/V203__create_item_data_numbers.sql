CREATE TABLE IF NOT EXISTS item_data_numbers (
    id                  BIGSERIAL PRIMARY KEY,
    version             INT NOT NULL DEFAULT 0,
    created             TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    item_data_id        BIGINT NOT NULL REFERENCES item_data(id) ON DELETE CASCADE,
    number              VARCHAR(100) NOT NULL,
    number_type         VARCHAR(30),
    packaging_unit_id   BIGINT,
    index               INT NOT NULL DEFAULT 0
);

CREATE INDEX idx_item_data_numbers_item ON item_data_numbers(item_data_id);
CREATE INDEX idx_item_data_numbers_number ON item_data_numbers(number);
