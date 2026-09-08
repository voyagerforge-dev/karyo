CREATE TABLE IF NOT EXISTS delivery_order_lines (
    id                 BIGSERIAL PRIMARY KEY,
    version            INT NOT NULL DEFAULT 0,
    created            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    delivery_order_id  BIGINT NOT NULL REFERENCES delivery_orders(id) ON DELETE CASCADE,
    line_number        INT NOT NULL,
    item_data_id       BIGINT NOT NULL,
    item_data_number   VARCHAR(100) NOT NULL,
    amount             NUMERIC(17,4) NOT NULL,
    reserved_amount    NUMERIC(17,4) NOT NULL DEFAULT 0,
    state              INT NOT NULL DEFAULT 0,
    lot_number         VARCHAR(255)
);

CREATE INDEX idx_delivery_order_lines_order ON delivery_order_lines(delivery_order_id);
CREATE INDEX idx_delivery_order_lines_item ON delivery_order_lines(item_data_id);
