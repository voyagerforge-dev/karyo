-- Forensic identity for journal rows (WORKLIST 2026-07-20 "cannot be backfilled" row —
-- decision: accept historical rows as-is, make future rows self-identifying).
-- Nullable: rows written before this migration stay NULL, honestly.
ALTER TABLE inventory_journals ADD COLUMN stock_unit_id BIGINT;
ALTER TABLE inventory_journals ADD COLUMN item_data_id BIGINT;
