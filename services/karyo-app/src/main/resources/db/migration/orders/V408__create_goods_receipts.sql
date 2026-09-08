CREATE TABLE IF NOT EXISTS goods_receipts (
    id                    BIGSERIAL PRIMARY KEY,
    version               INT NOT NULL DEFAULT 0,
    created               TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified              TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id             BIGINT NOT NULL,
    receipt_number        VARCHAR(100) NOT NULL,
    asn_id                BIGINT REFERENCES asns(id),
    carrier_name          VARCHAR(255),
    delivery_note_number  VARCHAR(100),
    notes                 VARCHAR(2000),
    state                 INT NOT NULL DEFAULT 0,
    UNIQUE(client_id, receipt_number)
);

CREATE INDEX idx_goods_receipts_client ON goods_receipts(client_id);
CREATE INDEX idx_goods_receipts_state ON goods_receipts(state);
CREATE INDEX idx_goods_receipts_asn ON goods_receipts(asn_id);
