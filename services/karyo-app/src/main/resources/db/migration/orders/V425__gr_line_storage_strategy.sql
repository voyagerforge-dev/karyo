-- Per-receipt-line storage strategy override (inbound-completion row 7 residual): an
-- ID-only, cross-module reference into the layout module's storage_strategies table --
-- no FK, per the cross-module-references convention (module boundaries stay splittable).
ALTER TABLE goods_receipt_lines ADD COLUMN storage_strategy_id BIGINT;
