-- B1: ASN sender (who dispatched the shipment, distinct from supplier_name = who
-- supplies the goods) + lifecycle timestamps mirroring delivery_orders.started/finished.
ALTER TABLE asns ADD COLUMN sender_name VARCHAR(255);
ALTER TABLE asns ADD COLUMN started TIMESTAMPTZ;
ALTER TABLE asns ADD COLUMN finished TIMESTAMPTZ;
