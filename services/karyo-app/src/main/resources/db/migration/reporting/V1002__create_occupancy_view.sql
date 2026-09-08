-- Per-location occupancy for the warehouse heatmap.
-- occupied = a unit load sits on the location (same signal as kpi_utilization_current).
-- locked   = lock_type <> 0.
CREATE VIEW kpi_location_occupancy AS
SELECT l.client_id,
       l.id          AS location_id,
       l.name        AS location_name,
       l.zone_id,
       z.name        AS zone_name,
       l.order_index,
       EXISTS (SELECT 1 FROM unit_loads u WHERE u.storage_location_id = l.id) AS occupied,
       (l.lock_type <> 0)                                                     AS locked
FROM storage_locations l
LEFT JOIN zones z ON z.id = l.zone_id;
