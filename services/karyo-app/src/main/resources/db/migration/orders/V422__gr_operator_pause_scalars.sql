-- B7: goods-receipt operator claim/release, orthogonal pause, and the header
-- scalars myWMS carried (prio / receiptDate / dock storageLocation).
--
-- operator_id is PURE METADATA (the claim never moves state — see
-- GoodsReceiptService.claim KDoc); paused_at is the ORTHOGONAL pause model
-- (state untouched, so resume is lossless — unlike myWMS's PAUSE state jump,
-- which recomputed and lost STARTED on resume). prio copies the
-- DeliveryOrder.prio convention (INT NOT NULL DEFAULT 50); receipt_date is the
-- operator-entered (backdatable) physical-arrival date; the dock pair is a
-- denormalized id+name per the GoodsReceiptLine.location_id/location_name
-- precedent (unvalidated against layout).
ALTER TABLE goods_receipts ADD COLUMN prio INT NOT NULL DEFAULT 50;
ALTER TABLE goods_receipts ADD COLUMN receipt_date DATE;
ALTER TABLE goods_receipts ADD COLUMN dock_location_id BIGINT;
ALTER TABLE goods_receipts ADD COLUMN dock_location_name VARCHAR(100);
ALTER TABLE goods_receipts ADD COLUMN operator_id VARCHAR(100);
ALTER TABLE goods_receipts ADD COLUMN paused_at TIMESTAMPTZ;
