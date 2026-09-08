ALTER TABLE count_orders ADD COLUMN IF NOT EXISTS operator_id VARCHAR(100);
ALTER TABLE count_orders ADD COLUMN IF NOT EXISTS started_by  VARCHAR(100);
CREATE INDEX IF NOT EXISTS idx_count_orders_operator ON count_orders (client_id, operator_id);
