-- L4 (locations-layout sprint, Task 7): myWMS LocationType field/section lifting capacities.
-- Nullable = unrestricted (regression pin: every existing row carries neither column, so the
-- finder's new group-capacity check is a no-op on a freshly migrated DB).
ALTER TABLE location_types
    ADD COLUMN field_lifting_capacity NUMERIC(16,3),
    ADD COLUMN section_lifting_capacity NUMERIC(16,3);
