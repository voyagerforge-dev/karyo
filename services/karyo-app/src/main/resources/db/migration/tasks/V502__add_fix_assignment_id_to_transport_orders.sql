-- Replenishment idempotency breadcrumb: links a REPLENISH transport order back to the
-- FixAssignment that triggered it, so a re-scan skips faces that already have an open task.
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS fix_assignment_id BIGINT;

-- Non-unique: a face is replenished many times over its life; idempotency is "no OPEN task",
-- enforced by query (state not finished/canceled), not by a DB constraint.
CREATE INDEX IF NOT EXISTS idx_transport_orders_fix_assignment
    ON transport_orders(fix_assignment_id)
    WHERE fix_assignment_id IS NOT NULL;
