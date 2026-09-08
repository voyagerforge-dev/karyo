-- Task 5 (outbound-completion sprint): shipment claim/release/pause/resume lifecycle.
--
-- operator_id is PURE METADATA (mirrors GoodsReceipt.operatorId / PickOrder.operatorId --
-- claim/release never move `state`). paused_at is the ORTHOGONAL pause model (GoodsReceipt V422 /
-- TransportOrder PT18 precedent): `state` NEVER moves and ShipmentState.canAdvanceTo is untouched,
-- so resume is lossless. While paused, pack/manifest/dispatch refuse with 409 (see
-- ShippingLifecycleService); claim is kept, not cleared, while paused.
ALTER TABLE shipments ADD COLUMN operator_id VARCHAR(100);
ALTER TABLE shipments ADD COLUMN paused_at TIMESTAMPTZ;
