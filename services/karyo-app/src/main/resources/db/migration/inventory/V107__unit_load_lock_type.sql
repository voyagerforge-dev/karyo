-- A2-3 (six-hard-items §2): pallet-level lock. Enforcement rides the cascade to
-- stock_units.lock_type (selection already excludes lockType != 0).
ALTER TABLE unit_loads ADD COLUMN lock_type INT NOT NULL DEFAULT 0;
