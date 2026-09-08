-- St4: unit-load identity on count lines, snapshotted at generateOrderForLocation time.
-- Nullable -- old rows (pre-migration) and the demo generator (writes count rows directly)
-- have no unit-load context; the frontend groups a null unitLoadLabel under "Loose stock".
ALTER TABLE count_lines ADD COLUMN unit_load_id BIGINT;
ALTER TABLE count_lines ADD COLUMN unit_load_label VARCHAR(255);
