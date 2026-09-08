-- V430: wave membership (wave module, Advanced Fulfillment pack). ID only, no FK (house rule).
ALTER TABLE delivery_orders ADD COLUMN wave_id BIGINT;
CREATE INDEX idx_delivery_orders_wave ON delivery_orders (wave_id) WHERE wave_id IS NOT NULL;
