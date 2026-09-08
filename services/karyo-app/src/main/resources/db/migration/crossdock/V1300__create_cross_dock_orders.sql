-- V1300: cross_dock_orders (crossdock module, Advanced Fulfillment pack)
CREATE TABLE cross_dock_orders (
    id                     BIGSERIAL    PRIMARY KEY,
    version                INT          NOT NULL DEFAULT 0,
    created                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    modified               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    client_id              BIGINT       NOT NULL DEFAULT 0,
    order_number           VARCHAR(100) NOT NULL UNIQUE,
    state                  INTEGER      NOT NULL DEFAULT 100,
    cross_dock_type        VARCHAR(20)  NOT NULL,
    goods_receipt_line_id  BIGINT       NOT NULL,
    delivery_order_line_id BIGINT       NOT NULL,
    item_data_id           BIGINT       NOT NULL,
    amount                 NUMERIC(17,4) NOT NULL,
    unit_load_id           BIGINT,
    stock_unit_id          BIGINT,
    transport_order_id     BIGINT,
    staging_location_id    BIGINT,
    staging_deadline       TIMESTAMPTZ
);
CREATE INDEX idx_cross_dock_orders_grl ON cross_dock_orders (goods_receipt_line_id);
CREATE INDEX idx_cross_dock_orders_state_deadline ON cross_dock_orders (state, staging_deadline);
CREATE INDEX idx_cross_dock_orders_transport ON cross_dock_orders (transport_order_id);
