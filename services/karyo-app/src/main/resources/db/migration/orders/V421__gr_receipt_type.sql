-- B2: goods receipts gain an inbound REASON — myWMS GoodsReceiptType parity
-- (NORMAL = 0, RETOUR = 1). Until now the only discriminator was asn_id
-- (NULL = blind, NOT NULL = ASN-bound). Every existing receipt is regular
-- inbound -> DEFAULT 0. The type is immutable after create (no update path).
ALTER TABLE goods_receipts ADD COLUMN receipt_type INT NOT NULL DEFAULT 0;
