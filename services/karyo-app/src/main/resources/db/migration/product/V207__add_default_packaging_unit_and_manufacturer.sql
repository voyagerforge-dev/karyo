-- C3 (myWMS parity). ID-only reference, same-module; no FK by deliberate style
-- consistency with default_unit_load_type_id / default_storage_strategy_id.
ALTER TABLE item_data ADD COLUMN default_packaging_unit_id BIGINT;
ALTER TABLE item_data_numbers ADD COLUMN manufacturer_name VARCHAR(255);
