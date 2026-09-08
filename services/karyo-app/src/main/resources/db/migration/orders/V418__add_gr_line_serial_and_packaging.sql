-- B4 (partial): what was received, recorded on the goods-receipt line and threaded
-- onward to the created stock unit (stock_units.serial_number / packaging_unit_id
-- already exist since V103).
-- packaging_unit_id is an ID-only cross-module reference into the product module
-- (packaging_units) — no FK, per the cross-module reference convention.
ALTER TABLE goods_receipt_lines ADD COLUMN serial_number VARCHAR(255);
ALTER TABLE goods_receipt_lines ADD COLUMN packaging_unit_id BIGINT;
