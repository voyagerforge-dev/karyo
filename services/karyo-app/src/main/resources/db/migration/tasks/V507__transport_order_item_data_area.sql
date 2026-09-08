-- R12b (replenishment sprint Task 6): area-level replenishment provenance.
-- Nullable, additive; no FK (cross-module id, same convention as fix_assignment_id/V502).
ALTER TABLE karyo.transport_orders ADD COLUMN item_data_area_id BIGINT;
CREATE INDEX idx_transport_orders_item_data_area ON karyo.transport_orders (item_data_area_id);
