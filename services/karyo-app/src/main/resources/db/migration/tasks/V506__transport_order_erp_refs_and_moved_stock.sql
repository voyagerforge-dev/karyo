-- PT17 (putaway-transport sprint, Task 4): ERP references + moved-stock denorm + confirmed
-- amount. Karyo-native additions -- myWMS's TransportOrder has no equivalent to be faithful to.
--
-- ERP refs mirror the naming divergence already established across modules: orders
-- (DeliveryOrder/DeliveryOrderLine/Asn) uses `external_number` VARCHAR(100); inventory
-- (UnitLoad) uses `external_id` VARCHAR(255). TransportOrder carries BOTH here rather than
-- picking a side.
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS external_number VARCHAR(100);
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS external_id VARCHAR(255);

-- Moved-stock denorm, stamped at creation time (see ConfirmVariantService.denormalizeAtCreation)
-- when the order's own unit load carries EXACTLY ONE live (non-DELETABLE) stock unit; a
-- multi-stock unit load leaves all five columns null -- an honest gap, not a guess.
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS item_data_id BIGINT;
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS item_data_number VARCHAR(100);
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS lot_number VARCHAR(255);
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS amount NUMERIC(17,4);
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS source_stock_unit_id BIGINT;

-- Written on EVERY confirm path (whole-UL move, confirm-merge, partial) -- the amount actually
-- confirmed, which may differ from `amount` above on a PARTIAL confirm. Null when `amount`
-- itself was never denormalized (multi-stock unit load at creation) on a plain whole-UL move.
ALTER TABLE transport_orders ADD COLUMN IF NOT EXISTS confirmed_amount NUMERIC(17,4);

-- No FKs on any of the above: ID-only cross-module references (item_data_id -> product,
-- source_stock_unit_id -> inventory), per convention.
