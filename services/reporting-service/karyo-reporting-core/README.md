# karyo-reporting-core

**Status:** Current module reference, 2026-09-08. Reporting uses direct queries and ordinary SQL
views over the operational schema, not Kafka projections or a scheduled materialized-view store.
It also persists saved report definitions, so the module as a whole is not read-only.

## Interfaces

| Route | Owner / behavior | Role |
|---|---|---|
| `GET /api/v1/insights/kpis?range=30D` | `KpiResource`, `KpiDashboardService`; ranges `7D`, `30D`, `90D`, `YTD` | `inventory-read` |
| `GET /api/v1/insights/occupancy` | `OccupancyResource`, `OccupancyService`; current zone-grid occupancy | `inventory-read` |
| `GET /api/v1/insights/volume-by-category` | `VolumeResource`, `CategoryVolumeService`; pick volume by product category | `inventory-read` |
| `GET/POST /api/v1/report-definitions`, `DELETE /{id}` | Saved report-definition store, not execution of arbitrary report SQL | `report-read` / `report-write` |

These resources currently pass `TenantContext.clientId` to their services. They do not all widen
reads through OPS `readScope()`. Do not generalize inventory's owner-scope helper behavior to this
module. This documents current behavior without changing permissions.

## Schema and calculation owners

Migrations live under `services/karyo-app/src/main/resources/db/migration/reporting/`.
Read the **whole chain**, not copied V1001 SQL:

- V1001 creates four KPI views: accuracy, throughput, cycle time, utilization.
- V1002 creates per-location occupancy; V1003 creates report definitions.
- V1004 excludes extinguish picks from throughput.
- V1005 excludes DELETABLE unit loads from occupancy/utilization.
- V1006 adds capacity and live unit-load counts to the occupancy view.

Applied migrations are immutable, including comments. A new view definition requires a forward
migration. `KpiViewRepository`, `OccupancyViewRepository` and `CategoryVolumeRepository` own current
native queries. Schema-qualify native view names (`karyo.*`); Hibernate's default schema does not
rewrite native SQL.

Important interpretation limits:

- Throughput's `units_shipped` is a shipment count, **not a quantity**. The dashboard's throughput
  tile/chart uses picked/received quantities instead. Group shipments now exist; do not assume
  every shipment is one delivery order or that joins without a foreign key imply one.
- Cycle-time aggregation uses sums of total hours and order count, not an average of daily
  averages. The existing view joins `shipments.delivery_order_id`; do not claim it attributes
  nullable group shipments through `shipment_orders` unless that query is changed and tested.
- Utilization is a current occupied/usable location ratio, not a historical trend. Occupancy
  excludes DELETABLE unit loads. Capacity utilization is separate from occupied-location ratio.
- Display uses locked-first state; physical occupancy still counts a locked-and-occupied location.
- Zone-grid heatmap is implemented. A physical x/y warehouse-coordinate floor plan is not.

Response DTOs in `karyo-reporting-api` are the exact output owner. Tests in `services/karyo-app`
exercise this module; static SQL excerpts alone do not prove KPI behavior.
