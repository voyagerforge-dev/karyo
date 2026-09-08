-- Carries the receipt line's storage strategy override (if any) onto the auto-created
-- PUTAWAY transport order -- PERSISTED so the re-resolve path (start on RESERVED with no
-- suggestion) can replay the same override; an unpersisted value would be silently
-- dropped there. ID-only, cross-module reference (layout storage_strategies), no FK.
ALTER TABLE transport_orders ADD COLUMN storage_strategy_id BIGINT;
