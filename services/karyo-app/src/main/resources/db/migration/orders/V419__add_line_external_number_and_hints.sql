-- D2: line-level external number (orders-module naming: external_number, matching
--     delivery_orders.external_number and asns.external_number — NOT inventory's external_id).
-- D3: per-order operator instruction hints for the picking/packing/shipping phases.
ALTER TABLE delivery_order_lines ADD COLUMN external_number VARCHAR(100);
ALTER TABLE delivery_orders ADD COLUMN picking_hint VARCHAR(500);
ALTER TABLE delivery_orders ADD COLUMN packing_hint VARCHAR(500);
ALTER TABLE delivery_orders ADD COLUMN shipping_hint VARCHAR(500);
