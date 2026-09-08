CREATE TABLE IF NOT EXISTS transport_orders (
    id                        BIGSERIAL PRIMARY KEY,
    version                   INT NOT NULL DEFAULT 0,
    created                   TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                  TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id                 BIGINT NOT NULL,
    order_number              VARCHAR(100) NOT NULL,
    transport_type            VARCHAR(20) NOT NULL,
    unit_load_id              BIGINT NOT NULL,
    unit_load_label           VARCHAR(255) NOT NULL,
    source_location_id        BIGINT NOT NULL,
    source_location_name      VARCHAR(100) NOT NULL,
    destination_location_id   BIGINT,
    destination_location_name VARCHAR(100),
    suggested_location_id     BIGINT,
    suggested_location_name   VARCHAR(100),
    state                     INT NOT NULL DEFAULT 0,
    prio                      INT NOT NULL DEFAULT 50,
    operator_id               VARCHAR(100),
    executor_type             VARCHAR(20) NOT NULL DEFAULT 'HUMAN',
    location_reservation_id   BIGINT,
    goods_receipt_line_id     BIGINT,
    note                      VARCHAR(500),
    started                   TIMESTAMPTZ,
    finished                  TIMESTAMPTZ,
    UNIQUE(client_id, order_number)
);

CREATE INDEX idx_transport_orders_client ON transport_orders(client_id);
CREATE INDEX idx_transport_orders_state ON transport_orders(state);
CREATE INDEX idx_transport_orders_type ON transport_orders(transport_type);
CREATE INDEX idx_transport_orders_operator ON transport_orders(operator_id);
CREATE INDEX idx_transport_orders_unit_load ON transport_orders(unit_load_id);
-- Idempotency guard for the receiving observer (one putaway task per receipt line).
CREATE UNIQUE INDEX idx_transport_orders_grl ON transport_orders(goods_receipt_line_id)
    WHERE goods_receipt_line_id IS NOT NULL;
