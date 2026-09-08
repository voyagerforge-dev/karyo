-- Phase B (B9 capacity, B10 temperature/handling-class/last-counted, B12 kind): honest
-- metadata columns on storage_locations. All nullable — un-set rows render as "—" on the
-- frontend rather than being fabricated.
ALTER TABLE storage_locations ADD COLUMN capacity INTEGER;
ALTER TABLE storage_locations ADD COLUMN temperature_zone VARCHAR(20);
ALTER TABLE storage_locations ADD COLUMN handling_class VARCHAR(20);
ALTER TABLE storage_locations ADD COLUMN kind VARCHAR(20);
ALTER TABLE storage_locations ADD COLUMN last_counted_at TIMESTAMP;
