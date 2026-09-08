-- Defect row 3 (2026-08-02 burndown): a terminated unit load (hard delete, or the cycle-count
-- soft flip to DELETABLE=1000) now releases its StorageLocation.allocation via
-- UnitLoadTrashedObserver, but kpi_utilization_current and kpi_location_occupancy still counted
-- a DELETABLE unit load's row as "occupied" (they only check EXISTS, not state) -- the soft-flip
-- and the allocation release would disagree. Adds `AND u.state <> 1000` to both EXISTS subqueries.
-- CREATE OR REPLACE keeps the LOCKED output-column contract of V1001/V1002 intact -- identical
-- column lists, predicate only.

CREATE OR REPLACE VIEW kpi_utilization_current AS
SELECT l.client_id,
       count(*) FILTER (WHERE EXISTS (
           SELECT 1 FROM unit_loads u WHERE u.storage_location_id = l.id AND u.state <> 1000
       ))                                                                       AS occupied,
       count(*)                                                                 AS usable
FROM storage_locations l
GROUP BY l.client_id;

CREATE OR REPLACE VIEW kpi_location_occupancy AS
SELECT l.client_id,
       l.id          AS location_id,
       l.name        AS location_name,
       l.zone_id,
       z.name        AS zone_name,
       l.order_index,
       EXISTS (SELECT 1 FROM unit_loads u WHERE u.storage_location_id = l.id AND u.state <> 1000) AS occupied,
       (l.lock_type <> 0)                                                     AS locked
FROM storage_locations l
LEFT JOIN zones z ON z.id = l.zone_id;
