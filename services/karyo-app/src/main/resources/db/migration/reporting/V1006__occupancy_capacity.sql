-- SC21: wire the never-consumed storage_locations.capacity column (nullable, Phase-B honest
-- metadata, V309) into the occupancy insights report as slot-level utilization.
-- CREATE OR REPLACE keeps the LOCKED output-column contract of V1001/V1002/V1005 intact --
-- this migration is append-only: V1005's SELECT list is reproduced verbatim and exactly two
-- columns are appended at the end (l.capacity, unit_load_count). No columns are dropped,
-- reordered, or retyped.

CREATE OR REPLACE VIEW kpi_location_occupancy AS
SELECT l.client_id,
       l.id          AS location_id,
       l.name        AS location_name,
       l.zone_id,
       z.name        AS zone_name,
       l.order_index,
       EXISTS (SELECT 1 FROM unit_loads u WHERE u.storage_location_id = l.id AND u.state <> 1000) AS occupied,
       (l.lock_type <> 0)                                                     AS locked,
       l.capacity,
       (SELECT COUNT(*) FROM unit_loads u WHERE u.storage_location_id = l.id AND u.state <> 1000) AS unit_load_count
FROM storage_locations l
LEFT JOIN zones z ON z.id = l.zone_id;
