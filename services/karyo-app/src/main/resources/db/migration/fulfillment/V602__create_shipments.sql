CREATE TABLE IF NOT EXISTS shipments (
    id                      BIGSERIAL PRIMARY KEY,
    version                 INT NOT NULL DEFAULT 0,
    created                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id               BIGINT NOT NULL,
    shipment_number         VARCHAR(40) NOT NULL,
    delivery_order_id       BIGINT NOT NULL,
    delivery_order_number   VARCHAR(40) NOT NULL,
    state                   INT NOT NULL DEFAULT 640,
    started                 TIMESTAMPTZ,
    finished                TIMESTAMPTZ,
    carrier_name            VARCHAR(80),
    carrier_service         VARCHAR(80),
    tracking_number         VARCHAR(80),
    shipped_at              TIMESTAMPTZ,
    UNIQUE (client_id, shipment_number)
);
CREATE INDEX idx_shipments_client ON shipments(client_id);
CREATE INDEX idx_shipments_delivery ON shipments(delivery_order_id);
CREATE INDEX idx_shipments_state ON shipments(client_id, state);

CREATE TABLE IF NOT EXISTS shipping_units (
    id                      BIGSERIAL PRIMARY KEY,
    version                 INT NOT NULL DEFAULT 0,
    created                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id               BIGINT NOT NULL,
    shipment_id             BIGINT NOT NULL REFERENCES shipments(id),
    shipping_unit_number    VARCHAR(50) NOT NULL,
    type                    VARCHAR(20) NOT NULL DEFAULT 'CARTON',
    weight                  NUMERIC(12,3) NOT NULL DEFAULT 0,
    state                   INT NOT NULL DEFAULT 650,
    unit_load_id            BIGINT,
    tracking_number         VARCHAR(80),
    UNIQUE (client_id, shipping_unit_number)
);
CREATE INDEX idx_shipping_units_client ON shipping_units(client_id);
CREATE INDEX idx_shipping_units_shipment ON shipping_units(shipment_id);

CREATE TABLE IF NOT EXISTS shipping_unit_lines (
    id                      BIGSERIAL PRIMARY KEY,
    version                 INT NOT NULL DEFAULT 0,
    created                 TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified                TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id               BIGINT NOT NULL,
    shipping_unit_id        BIGINT NOT NULL REFERENCES shipping_units(id),
    item_data_id            BIGINT NOT NULL,
    item_data_number        VARCHAR(60) NOT NULL,
    amount                  NUMERIC(17,4) NOT NULL,
    source_pick_id          BIGINT,
    lot_number              VARCHAR(60)
);
CREATE INDEX idx_shipping_unit_lines_client ON shipping_unit_lines(client_id);
CREATE INDEX idx_shipping_unit_lines_unit ON shipping_unit_lines(shipping_unit_id);
