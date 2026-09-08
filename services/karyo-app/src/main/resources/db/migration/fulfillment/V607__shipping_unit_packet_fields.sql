-- P2 (outbound-completion sprint, Task 2): shipping_units packet fields.
--
-- position_index: 1-based per-shipment sequence, immutable once assigned by PackingService.pack
-- (see the entity KDoc). DEFAULT 0 for existing rows only -- new rows always get a real value.
--
-- carrier_label: per-parcel carrier label reference, distinct from shipments.tracking_number
-- (which the manifest step stamps identically onto every unit today -- see packing-facts.md).
--
-- ship_to_*: flat nullable label/print override. NULL in every column (the default for existing
-- rows and for any new unit that doesn't set one) means "inherit the order's ship-to address" --
-- this is NOT multi-drop routing, just a per-parcel label override.
--
-- origin: Task 6/7's consumer (not read or written by this task). Added here per the sprint's
-- one-migration-for-the-whole-shipping_units-surface adjudication, so a later task doesn't need
-- its own ALTER TABLE against this same table.
ALTER TABLE shipping_units
    ADD COLUMN position_index      INT NOT NULL DEFAULT 0,
    ADD COLUMN carrier_label       VARCHAR(255),
    ADD COLUMN ship_to_name        VARCHAR(255),
    ADD COLUMN ship_to_street      VARCHAR(255),
    ADD COLUMN ship_to_street_number VARCHAR(50),
    ADD COLUMN ship_to_zip         VARCHAR(20),
    ADD COLUMN ship_to_city        VARCHAR(120),
    ADD COLUMN ship_to_country     VARCHAR(60),
    ADD COLUMN origin              VARCHAR(20) NOT NULL DEFAULT 'PACKOUT';

-- position_index is a 1-based contract, so legacy rows left at the DEFAULT 0 above are re-sequenced per shipment, ordered by id.
UPDATE shipping_units su
SET position_index = sub.rn
FROM (SELECT id, ROW_NUMBER() OVER (PARTITION BY shipment_id ORDER BY id) AS rn FROM shipping_units) sub
WHERE su.id = sub.id AND su.position_index = 0;
