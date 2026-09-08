CREATE TABLE IF NOT EXISTS pick_orders (
    id                      BIGSERIAL PRIMARY KEY,
    version                 INT NOT NULL DEFAULT 0,
    created                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id               BIGINT NOT NULL,
    pick_order_number       VARCHAR(40) NOT NULL,
    delivery_order_id       BIGINT NOT NULL,
    delivery_order_number   VARCHAR(40) NOT NULL,
    state                   INT NOT NULL DEFAULT 50,
    prio                    INT NOT NULL DEFAULT 50,
    operator_id             BIGINT,
    target_unit_load_id     BIGINT,
    started                 TIMESTAMPTZ,
    finished                TIMESTAMPTZ,
    UNIQUE (client_id, pick_order_number)
);
CREATE INDEX idx_pick_orders_client ON pick_orders(client_id);
CREATE INDEX idx_pick_orders_delivery ON pick_orders(delivery_order_id);
CREATE INDEX idx_pick_orders_state ON pick_orders(client_id, state);

CREATE TABLE IF NOT EXISTS picks (
    id                       BIGSERIAL PRIMARY KEY,
    version                  INT NOT NULL DEFAULT 0,
    created                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id                BIGINT NOT NULL,
    pick_order_id            BIGINT NOT NULL REFERENCES pick_orders(id),
    delivery_order_line_id   BIGINT NOT NULL,
    item_data_id             BIGINT NOT NULL,
    item_data_number         VARCHAR(60) NOT NULL,
    source_stock_unit_id     BIGINT NOT NULL,
    planned_amount           NUMERIC(17,4) NOT NULL,
    picked_amount            NUMERIC(17,4) NOT NULL DEFAULT 0,
    state                    INT NOT NULL DEFAULT 50,
    lot_number               VARCHAR(60),
    picking_type             VARCHAR(20) NOT NULL DEFAULT 'PICK',
    follow_up_for_pick_id    BIGINT,
    substituted_item_data_id BIGINT,
    target_stock_unit_id     BIGINT
);
CREATE INDEX idx_picks_client ON picks(client_id);
CREATE INDEX idx_picks_pick_order ON picks(pick_order_id);
CREATE INDEX idx_picks_state ON picks(client_id, state);
