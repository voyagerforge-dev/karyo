-- V611: Bulk Allocation Sprint B. A batch PickOrder minted by a BULK wave: the operator sees
-- SKU-aggregated pick units and confirms through /bulk-confirm (fan-out to per-line slices).
ALTER TABLE pick_orders ADD COLUMN bulk BOOLEAN NOT NULL DEFAULT FALSE;
