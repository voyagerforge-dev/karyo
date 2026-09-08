-- PT15 (putaway-transport sprint, Task 2): marks a StorageArea as a transfer-staging
-- waypoint. A transport order that completes onto a location in a transfer-staging area,
-- while still carrying a different real final target, triggers ChainContinuationService to
-- spin up a TRANSFER successor that carries the unit load the rest of the way. Default
-- FALSE: every existing area (and every area created without opting in) behaves exactly as
-- before -- no chaining.
ALTER TABLE storage_areas ADD COLUMN IF NOT EXISTS transfer_staging BOOLEAN NOT NULL DEFAULT FALSE;
