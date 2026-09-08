-- stock_unit_id / unit_load_id / location_id are ID-only references into the
-- inventory and layout modules (no cross-module FKs per ADR-004 conventions).
CREATE TABLE IF NOT EXISTS goods_receipt_lines (
    id                BIGSERIAL PRIMARY KEY,
    version           INT NOT NULL DEFAULT 0,
    created           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    goods_receipt_id  BIGINT NOT NULL REFERENCES goods_receipts(id) ON DELETE CASCADE,
    asn_line_id       BIGINT REFERENCES asn_lines(id),
    item_data_id      BIGINT NOT NULL,
    item_data_number  VARCHAR(100) NOT NULL,
    amount            NUMERIC(17,4) NOT NULL,
    location_id       BIGINT NOT NULL,
    location_name     VARCHAR(100) NOT NULL,
    unit_load_label   VARCHAR(255) NOT NULL,
    stock_unit_id     BIGINT NOT NULL,
    unit_load_id      BIGINT NOT NULL,
    lot_number        VARCHAR(255),
    best_before       DATE,
    qa_hold           BOOLEAN NOT NULL DEFAULT FALSE
);

CREATE INDEX idx_goods_receipt_lines_receipt ON goods_receipt_lines(goods_receipt_id);
CREATE INDEX idx_goods_receipt_lines_asn_line ON goods_receipt_lines(asn_line_id);
CREATE INDEX idx_goods_receipt_lines_stock ON goods_receipt_lines(stock_unit_id);
