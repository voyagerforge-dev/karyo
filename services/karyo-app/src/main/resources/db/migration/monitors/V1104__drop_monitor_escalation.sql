-- Row 25 (defect-burndown-4): esc/escMin were dead end-to-end (no engine consumer ever read
-- them off MonitorConfig) -- entity, both DTOs, resource echo, service patch-write, and the
-- FE rule-builder switch are all removed in the same change. Drop the columns for real.
ALTER TABLE monitor_config DROP COLUMN esc;
ALTER TABLE monitor_config DROP COLUMN esc_min;
