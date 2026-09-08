-- EXTINGUISH picks are stock-clearance, not customer throughput (WORKLIST M1 sibling).
-- CREATE OR REPLACE keeps the LOCKED output-column contract of V1001 intact.
CREATE OR REPLACE VIEW kpi_throughput_daily AS
WITH picked AS (
    SELECT client_id,
           date_trunc('day', modified)::date AS day,
           sum(picked_amount)               AS units
    FROM picks
    WHERE picked_amount > 0
      AND picking_type <> 'EXTINGUISH'
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
