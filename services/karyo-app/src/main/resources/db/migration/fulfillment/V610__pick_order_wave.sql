-- V610: wave membership + batch zone (wave module, Advanced Fulfillment pack). IDs only, no FK.
ALTER TABLE pick_orders ADD COLUMN wave_id BIGINT;
ALTER TABLE pick_orders ADD COLUMN batch_zone VARCHAR(50);
CREATE INDEX idx_pick_orders_wave ON pick_orders (wave_id) WHERE wave_id IS NOT NULL;
