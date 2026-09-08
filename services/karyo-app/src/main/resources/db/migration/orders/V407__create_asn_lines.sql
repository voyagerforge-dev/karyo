CREATE TABLE IF NOT EXISTS asn_lines (
    id                BIGSERIAL PRIMARY KEY,
    version           INT NOT NULL DEFAULT 0,
    created           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    asn_id            BIGINT NOT NULL REFERENCES asns(id) ON DELETE CASCADE,
    line_number       INT NOT NULL,
    item_data_id      BIGINT NOT NULL,
    item_data_number  VARCHAR(100) NOT NULL,
    expected_amount   NUMERIC(17,4) NOT NULL,
    received_amount   NUMERIC(17,4) NOT NULL DEFAULT 0,
    state             INT NOT NULL DEFAULT 0,
    lot_number        VARCHAR(255)
);

CREATE INDEX idx_asn_lines_asn ON asn_lines(asn_id);
CREATE INDEX idx_asn_lines_item ON asn_lines(item_data_id);
