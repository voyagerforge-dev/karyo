-- V431: order streaming (streaming module, Advanced Fulfillment pack). ID-only, no FK (house rule).
-- release_mode_override: per-order MANUAL|WAVE|STREAM override of the strategy's releaseMode
-- extension property (null = inherit). The three stamps are the streaming engine's claim and
-- escalation markers: first attempt, escalation, and terminal stall timestamps.
ALTER TABLE delivery_orders ADD COLUMN release_mode_override VARCHAR(10);
ALTER TABLE delivery_orders ADD COLUMN stream_first_attempt_at TIMESTAMPTZ;
ALTER TABLE delivery_orders ADD COLUMN stream_escalated_at TIMESTAMPTZ;
ALTER TABLE delivery_orders ADD COLUMN stream_stalled_at TIMESTAMPTZ;
CREATE INDEX idx_delivery_orders_stream_candidates
    ON delivery_orders (client_id, order_strategy_id, prio DESC, created ASC)
    WHERE state = 50 AND wave_id IS NULL AND stream_stalled_at IS NULL;
CREATE INDEX idx_delivery_orders_stream_inflight
    ON delivery_orders (client_id, stream_first_attempt_at)
    WHERE stream_first_attempt_at IS NOT NULL;
