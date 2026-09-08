-- V612: Bulk Allocation Sprint C, cross-order (consolidation-group) shipments.
-- A group shipment has delivery_order_id NULL and names its members in shipment_orders;
-- per-order shipments are unchanged. Same precedent as batch PickOrders (V605/V610).
-- shipment_orders.delivery_order_number is VARCHAR(100), matching delivery_orders.order_number
-- (orders V402) and the V606 denorm-width rule -- denorm width must equal source width, the same
-- reasoning that widened shipments.delivery_order_number to VARCHAR(100) there.
ALTER TABLE shipments ALTER COLUMN delivery_order_id DROP NOT NULL;
ALTER TABLE shipments ALTER COLUMN delivery_order_number DROP NOT NULL;
ALTER TABLE shipments ADD COLUMN consolidation_group_id BIGINT;
ALTER TABLE shipments ADD COLUMN wave_id BIGINT;
CREATE INDEX idx_shipments_group ON shipments (consolidation_group_id) WHERE consolidation_group_id IS NOT NULL;

CREATE TABLE shipment_orders (
    id                    BIGSERIAL    PRIMARY KEY,
    version               INT          NOT NULL DEFAULT 0,
    created               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    modified              TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    client_id             BIGINT       NOT NULL,
    shipment_id           BIGINT       NOT NULL REFERENCES shipments(id),
    delivery_order_id     BIGINT       NOT NULL,
    delivery_order_number VARCHAR(100) NOT NULL,
    UNIQUE (shipment_id, delivery_order_id)
);
CREATE INDEX idx_shipment_orders_order ON shipment_orders (client_id, delivery_order_id);

ALTER TABLE shipping_unit_lines ADD COLUMN delivery_order_id BIGINT;
ALTER TABLE shipping_unit_lines ADD COLUMN delivery_order_line_id BIGINT;
CREATE INDEX idx_shipping_unit_lines_order_line ON shipping_unit_lines (client_id, delivery_order_line_id)
    WHERE delivery_order_line_id IS NOT NULL;
