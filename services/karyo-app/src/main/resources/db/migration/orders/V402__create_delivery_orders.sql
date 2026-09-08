CREATE TABLE IF NOT EXISTS delivery_orders (
    id                 BIGSERIAL PRIMARY KEY,
    version            INT NOT NULL DEFAULT 0,
    created            TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id          BIGINT NOT NULL,
    order_number       VARCHAR(100) NOT NULL,
    external_number    VARCHAR(100),
    customer_name      VARCHAR(255),
    delivery_date      DATE,
    prio               INT NOT NULL DEFAULT 50,
    notes              VARCHAR(2000),
    state              INT NOT NULL DEFAULT 0,
    order_strategy_id  BIGINT REFERENCES order_strategies(id),
    started            TIMESTAMPTZ,
    finished           TIMESTAMPTZ,
    UNIQUE(client_id, order_number)
);

CREATE INDEX idx_delivery_orders_client ON delivery_orders(client_id);
CREATE INDEX idx_delivery_orders_state ON delivery_orders(state);
CREATE INDEX idx_delivery_orders_number ON delivery_orders(order_number);
