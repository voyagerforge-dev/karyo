-- Row 10. destination_location_id is a StorageLocation reference. Public behavioral contract:
-- docs/functional/picking.md#23-pick-order-generation. It identifies which location inside this
-- warehouse the order's work is bound for. It is NOT the customer address (the street/city/
-- country columns from V414) and NOT the per-parcel label override (shipping_units.ship_to_*
-- from V607). Cross-module id-only reference, so no foreign key.
ALTER TABLE karyo.delivery_orders ADD COLUMN destination_location_id BIGINT;

-- Row 10. Operator claim. Pure metadata: claiming never moves state, matching the GoodsReceipt
-- V422 and Shipment V608 precedents rather than the pick order's state-moving claim.
ALTER TABLE karyo.delivery_orders ADD COLUMN operator_id VARCHAR(255);

-- Row 10. The party named as sender on this order's outbound paperwork, the outbound counterpart
-- of asns.sender_name (V417). Karyo-native: the behavioral corpus does not cover a sender on a
-- delivery order.
ALTER TABLE karyo.delivery_orders ADD COLUMN sender_name VARCHAR(255);
