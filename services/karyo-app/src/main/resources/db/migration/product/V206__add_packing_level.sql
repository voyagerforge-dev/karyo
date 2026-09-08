-- C2 (myWMS parity): ordinal nesting rank of a packaging unit.
-- 0 = base/each, 1 = carton, 2 = layer, 3 = pallet (convention, not enforced).
ALTER TABLE packaging_units ADD COLUMN packing_level INT NOT NULL DEFAULT 0;
