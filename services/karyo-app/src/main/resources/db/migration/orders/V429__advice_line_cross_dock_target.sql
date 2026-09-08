-- V429: cross-dock target on ASN lines (pre-distributed cross-docking, Advanced Fulfillment)
ALTER TABLE asn_lines ADD COLUMN cross_dock_delivery_order_id BIGINT;
