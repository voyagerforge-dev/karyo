-- Row 8. myWMS OrderStrategy flags. All default FALSE / NULL, which reproduces today's behavior
-- exactly. sendToShipping defaults TRUE in legacy myWMS; it defaults FALSE here deliberately,
-- because flipping it would silently change the state every existing order comes to rest in.
ALTER TABLE karyo.order_strategies ADD COLUMN send_to_packing BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE karyo.order_strategies ADD COLUMN send_to_shipping BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE karyo.order_strategies ADD COLUMN create_shipping_order BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE karyo.order_strategies ADD COLUMN create_type_orders BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE karyo.order_strategies ADD COLUMN default_destination_location_id BIGINT;
