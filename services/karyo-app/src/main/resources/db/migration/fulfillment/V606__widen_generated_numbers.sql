-- Widen generated pick, shipment and shipping-unit numbers for SequenceNumberService.
--
-- THREE independent widenings, for three independent reasons -- conflating them (as the first
-- cut of this migration did) undersized two of the three:
--
-- 1) SEQUENCE-GATE columns: pick_orders.pick_order_number / shipments.shipment_number are the
--    values SequenceNumberService.next(..., maxLength, isUnique) itself is asked to keep within
--    maxLength -- so THESE columns just need maxLength itself to be a sane width, and the 422
--    TooLong guard at the sequence gate does the rest for anything longer. They embed
--    "PO-{deliveryOrder.orderNumber}-..." / "SHP-{deliveryOrderNumber}-..." followed by
--    SequenceNumberService's TIMESTAMP_RANDOM tail ("-{13-digit millis}-{3-digit rand}", 18
--    chars) -- longer than the nanoTime().takeLast(6) tail these columns were originally sized
--    for (VARCHAR(40), V601/V602). At VARCHAR(40), even a completely ordinary system-generated
--    order number ("DO-{millis}-{rand}", 20 chars) already overflowed once embedded: "PO-" (3) +
--    20 + the 18-char tail = 41 > 40 -- every default-numbered order would have 422'd, not just a
--    deliberately pathological one. Widened to VARCHAR(80): "PO-"/"SHP-" + the 18-char tail
--    leaves ~57-59 chars of headroom for the embedded order number -- comfortably above every
--    system-generated shape and any realistic human-entered one, while an order number pushed
--    toward delivery_orders.order_number's own 100-char cap (orders V402) still overflows 80 and
--    correctly surfaces 422 SequenceException.TooLong instead of a DB error.
--
-- 2) DENORM columns: pick_orders.delivery_order_number / shipments.delivery_order_number are
--    PLAIN COPIES of delivery_orders.order_number (PickOrderService.releaseToPicking sets
--    `deliveryOrderNumber = order.orderNumber` directly; PackingService.openPacking propagates
--    that same value onto the Shipment) -- this write path never goes through
--    SequenceNumberService or its maxLength gate at all, so widening the sequence-gate columns
--    above does nothing for it. An order number of 41-59 chars comfortably passes the (1)
--    sequence gate (its embedded pick/shipment number stays under 80) but would still overflow
--    the OLD VARCHAR(40) denorm columns on the raw, unprefixed copy. Fix: denorm width must equal
--    source width -- widened to VARCHAR(100) to match delivery_orders.order_number exactly, so
--    every order number legal at create-time (@Size(max=100)) fits here unconditionally.
--
-- 3) DERIVED column: shipping_units.shipping_unit_number is built in PackingService.pack by
--    concatenating the shipment number, the literal "-SU", and the 1-based unit index -- shipment
--    number can now be up to 80 chars (the widened (1) column), so the derived value can reach
--    80 + "-SU" (3) + the unit index's digits.
--    The old VARCHAR(50) already overflowed for embedded delivery-order numbers >= 25 chars
--    (a WORSE regression than either of the above: openPacking would succeed, and only the
--    packout insert itself would 500, stranding the shipment in PACKING with re-open then
--    refused 409 "a shipment already exists"). Widened to VARCHAR(90): 80 + 3 + headroom for a
--    multi-digit shipping-unit index.
--
-- Forward-only, additive -- no data rewrite, existing (short) values are untouched.
ALTER TABLE pick_orders
    ALTER COLUMN pick_order_number TYPE VARCHAR(80),
    ALTER COLUMN delivery_order_number TYPE VARCHAR(100);

ALTER TABLE shipments
    ALTER COLUMN shipment_number TYPE VARCHAR(80),
    ALTER COLUMN delivery_order_number TYPE VARCHAR(100);

ALTER TABLE shipping_units
    ALTER COLUMN shipping_unit_number TYPE VARCHAR(90);
