-- Row 8 (createTypeOrders / defaultDestination arc).
-- Public behavioral contract: docs/functional/picking.md#23-pick-order-generation.
-- stamps a destination on the generated picking order itself, not just on the delivery order:
-- Karyo releases one delivery order at a time, so the resolution degenerates to
-- `order.destinationLocationId ?: strategy.defaultDestinationLocationId`, computed once per
-- release and stamped on every PickOrder created from it. Cross-module id-only reference (the
-- StorageLocation lives in the layout module), so no foreign key -- same convention as
-- delivery_orders.destination_location_id (orders V427).
ALTER TABLE pick_orders ADD COLUMN destination_location_id BIGINT;
