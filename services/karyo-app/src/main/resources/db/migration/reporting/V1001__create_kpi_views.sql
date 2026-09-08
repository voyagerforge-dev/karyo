-- KPI views for the v1.5 Insight dashboard.
-- OUTPUT columns are a LOCKED contract (later tasks depend on these exact names/shapes).
-- Source SQL was verified against the live schema (2026-06-28).
--
-- Schema notes:
--   * goods_receipt_lines has no client_id column — joined to goods_receipts to get it.
--   * No shipment_lines table exists; units_shipped = count(*) of dispatched shipments that day.
--   * shipments.delivery_order_id is the confirmed FK to delivery_orders.id for cycle-time.
--   * unit_loads.storage_location_id (NOT NULL) is used for occupancy — live, never stale.
--   * usable = all storage_locations for the tenant (lock_type not filtered: point-in-time snapshot).
--   * Cycle-time stores SUMS (order_count + total_hours) so windowed avg = sum(total_hours)/sum(order_count).

-- Inventory accuracy: clean cycle-count lines / total counted, per day.
CREATE VIEW kpi_accuracy_daily AS
SELECT client_id,
       date_trunc('day', created)::date                                    AS day,
       count(*) FILTER (WHERE counted_amount = planned_amount)             AS accurate_lines,
       count(*) FILTER (WHERE counted_amount IS NOT NULL)                  AS total_lines
FROM count_lines
GROUP BY client_id, date_trunc('day', created)::date;

-- Throughput: units picked / shipped / received per day.
-- units_picked  : sum of picked_amount on completed picks (bucketed by modified = when picking was recorded).
-- units_shipped : count of dispatched shipments that day (no shipment_lines table exists; each shipment = 1 unit).
-- units_received: sum of received amount from goods_receipt_lines, joined to goods_receipts for client_id.
CREATE VIEW kpi_throughput_daily AS
WITH picked AS (
    SELECT client_id,
           date_trunc('day', modified)::date AS day,
           sum(picked_amount)               AS units
    FROM picks
    WHERE picked_amount > 0
    GROUP BY client_id, date_trunc('day', modified)::date
),
shipped AS (
    SELECT client_id,
           date_trunc('day', shipped_at)::date AS day,
           count(*)::numeric                   AS units
    FROM shipments
    WHERE shipped_at IS NOT NULL
    GROUP BY client_id, date_trunc('day', shipped_at)::date
),
received AS (
    SELECT gr.client_id,
           date_trunc('day', grl.created)::date AS day,
           sum(grl.amount)                      AS units
    FROM goods_receipt_lines grl
    JOIN goods_receipts gr ON gr.id = grl.goods_receipt_id
    GROUP BY gr.client_id, date_trunc('day', grl.created)::date
)
SELECT coalesce(p.client_id, s.client_id, r.client_id)                        AS client_id,
       coalesce(p.day,       s.day,       r.day)                               AS day,
       coalesce(p.units, 0)                                                    AS units_picked,
       coalesce(s.units, 0)                                                    AS units_shipped,
       coalesce(r.units, 0)                                                    AS units_received
FROM      picked   p
FULL JOIN shipped  s ON s.client_id = p.client_id AND s.day = p.day
FULL JOIN received r ON r.client_id = coalesce(p.client_id, s.client_id)
                     AND r.day      = coalesce(p.day,       s.day);

-- Order cycle time: hours from delivery order created → shipment shipped_at, bucketed by ship day.
-- Stores SUMS so a downstream windowed average = sum(total_hours) / sum(order_count).
-- JOIN: shipments.delivery_order_id → delivery_orders.id (verified against live schema).
CREATE VIEW kpi_cycle_time_daily AS
SELECT s.client_id,
       date_trunc('day', s.shipped_at)::date                                   AS day,
       count(*)                                                                 AS order_count,
       sum(extract(epoch FROM (s.shipped_at - o.created)) / 3600.0)            AS total_hours
FROM shipments s
JOIN delivery_orders o ON o.id = s.delivery_order_id
                       AND o.client_id = s.client_id
WHERE s.shipped_at IS NOT NULL
GROUP BY s.client_id, date_trunc('day', s.shipped_at)::date;

-- Utilization: current occupied vs usable storage locations (point-in-time, no history).
-- occupied = location has at least one unit load parked on it (live join, can't be stale).
-- usable   = all storage locations for the tenant (no lock_type filter; full capacity denominator).
CREATE VIEW kpi_utilization_current AS
SELECT l.client_id,
       count(*) FILTER (WHERE EXISTS (
           SELECT 1 FROM unit_loads u WHERE u.storage_location_id = l.id
       ))                                                                       AS occupied,
       count(*)                                                                 AS usable
FROM storage_locations l
GROUP BY l.client_id;
