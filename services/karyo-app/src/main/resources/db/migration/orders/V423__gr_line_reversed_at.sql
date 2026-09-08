-- B3 (six-hard-items §3, option B3-2): a reversed line stays on the receipt for audit.
ALTER TABLE goods_receipt_lines ADD COLUMN reversed_at TIMESTAMPTZ NULL;
