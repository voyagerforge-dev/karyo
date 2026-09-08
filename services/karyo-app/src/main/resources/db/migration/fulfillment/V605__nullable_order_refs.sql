-- Stock-clearance (extinguish) picks (WORKLIST row 20): an extinguish PickOrder has NO backing
-- DeliveryOrder at all (myWMS ExtinguishOrderGenerator: deliveryOrderLine = null is legitimate).
-- Drops NOT NULL on the three order-reference columns V601 created as NOT NULL, so an EXT
-- PickOrder/Pick can persist with these genuinely absent rather than a fabricated sentinel (e.g.
-- deliveryOrderId = 0). Every existing (order-bound) row is unaffected -- forward-only, additive,
-- no data rewrite.
ALTER TABLE picks
    ALTER COLUMN delivery_order_line_id DROP NOT NULL;

ALTER TABLE pick_orders
    ALTER COLUMN delivery_order_id DROP NOT NULL,
    ALTER COLUMN delivery_order_number DROP NOT NULL;
