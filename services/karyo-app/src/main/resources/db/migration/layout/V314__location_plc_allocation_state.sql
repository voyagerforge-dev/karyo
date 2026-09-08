-- L3 (locations-layout sprint, Task 6): myWMS `plcCode` (free-text automation-system
-- address, search/display only — zero finder semantics) and `allocationState` (int,
-- default 0 = searchable; a non-zero value is an operator "mark full/blocked" that the
-- putaway finder must exclude — see LocationFinderService/StorageLocationRepository).
ALTER TABLE storage_locations ADD COLUMN plc_code VARCHAR(64);
ALTER TABLE storage_locations ADD COLUMN allocation_state INTEGER NOT NULL DEFAULT 0;
