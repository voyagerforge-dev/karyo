CREATE TABLE goods_receipt_asns (
    goods_receipt_id BIGINT NOT NULL REFERENCES goods_receipts(id),
    asn_id           BIGINT NOT NULL REFERENCES asns(id),
    PRIMARY KEY (goods_receipt_id, asn_id)
);
CREATE INDEX idx_goods_receipt_asns_asn ON goods_receipt_asns(asn_id);
INSERT INTO goods_receipt_asns (goods_receipt_id, asn_id)
    SELECT id, asn_id FROM goods_receipts WHERE asn_id IS NOT NULL;
INSERT INTO goods_receipt_asns (goods_receipt_id, asn_id)
    SELECT DISTINCT l.goods_receipt_id, al.asn_id
    FROM goods_receipt_lines l JOIN asn_lines al ON al.id = l.asn_line_id
    WHERE l.asn_line_id IS NOT NULL
    ON CONFLICT DO NOTHING;
ALTER TABLE goods_receipts DROP COLUMN asn_id;
