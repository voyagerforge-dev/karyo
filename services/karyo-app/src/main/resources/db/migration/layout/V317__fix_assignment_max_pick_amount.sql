-- L7 (locations-layout sprint, Task 9): myWMS FixAssignment.maxPickAmount — a SOFT per-pick
-- ceiling on a fixed picking slot. When set and less than a single pick's requested amount,
-- the fixed slot is skipped for that request (forces a split across other stock, or a
-- shortfall per existing selection semantics) — it does NOT cap total stock held at the
-- location. Nullable: absent = today's unbounded behavior (regression-pin default).
ALTER TABLE fix_assignments ADD COLUMN max_pick_amount NUMERIC(17, 4);
