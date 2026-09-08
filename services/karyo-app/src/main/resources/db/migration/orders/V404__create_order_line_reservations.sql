CREATE TABLE IF NOT EXISTS order_line_reservations (
    id             BIGSERIAL PRIMARY KEY,
    version        INT NOT NULL DEFAULT 0,
    created        TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified       TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    line_id        BIGINT NOT NULL REFERENCES delivery_order_lines(id) ON DELETE CASCADE,
    stock_unit_id  BIGINT NOT NULL,
    amount         NUMERIC(17,4) NOT NULL
);

CREATE INDEX idx_order_line_reservations_line ON order_line_reservations(line_id);
CREATE INDEX idx_order_line_reservations_stock ON order_line_reservations(stock_unit_id);
