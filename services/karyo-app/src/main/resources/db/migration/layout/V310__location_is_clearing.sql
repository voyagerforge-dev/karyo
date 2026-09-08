-- A2-1 (six-hard-items §2.3): clearing is an explicit, queryable location flag —
-- NOT the free-text kind taxonomy, NOT a magic id (myWMS's id=1 is not ported).
ALTER TABLE storage_locations ADD COLUMN is_clearing BOOLEAN NOT NULL DEFAULT FALSE;
-- singleton: at most ONE clearing location per instance
CREATE UNIQUE INDEX idx_storage_locations_clearing ON storage_locations (is_clearing) WHERE is_clearing;
