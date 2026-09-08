# ADR-012: CQRS for Reporting Service

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md) — Premise void — the conclusion needs revisiting.
>
> This ADR chose CQRS **because** database-per-service meant the reporting module could not JOIN across
> service databases. That constraint no longer exists: there is one `karyo` schema and reporting can
> JOIN directly. The read models it specifies were to be populated by consuming Kafka events, and there
> is no broker.
>
> **Reporting today** uses direct queries and SQL views (e.g. the `V1001` KPI views, the B18
> volume-by-category native query). CQRS may still be justified at scale, but not by the reason given
> here. Treat this as an open question, not a live decision.


## Status
Partially superseded

## Context
Karyo WMS's reporting-service needs to provide:

1. **Operational dashboards:** Real-time KPIs (orders/hour, pick rate, inventory turns, location utilization)
2. **Pre-built reports:** Inventory snapshot, order fulfillment, operator productivity, location utilization
3. **Historical trend analysis:** Order volume trends, seasonal patterns, efficiency improvements over time
4. **Ad-hoc queries:** "Show me slow-moving stock in zone A" or "Which operators have the highest pick rate?"
5. **Data exports:** CSV, Excel, PDF for offline analysis

The challenge is that this data spans multiple services:
- **Inventory data** lives in inventory-service's database
- **Order data** lives in order-service's database
- **Task data** lives in task-service's database
- **Location data** lives in warehouse-layout-service's database

With database-per-service (see [ADR-004](superseded/ADR-004-database-per-service.md)), the reporting-service cannot JOIN across these databases. Three approaches exist:

1. **API aggregation:** Reporting-service queries each service's API at report time. Problem: slow for large datasets, puts load on operational services, pagination complexity.
2. **Direct database access:** Reporting-service connects to each service's database for read-only access. Problem: couples reporting to service internals, blocks independent schema evolution, read queries compete with operational writes.
3. **CQRS:** Reporting-service maintains its own read models, populated asynchronously by consuming Kafka events from all services.

## Decision
The reporting-service will use **CQRS (Command Query Responsibility Segregation)** with **dedicated read models** populated by consuming Kafka events from all operational services.

### Architecture

```
inventory-service ──────┐
                        │
order-service ──────────┤  Kafka Events
                        ├──────────────► reporting-service
task-service ───────────┤                     │
                        │                     │ Populate
layout-service ─────────┘                     │
                                              ▼
                                    ┌─────────────────────┐
                                    │  karyo_reporting     │
                                    │                      │
                                    │  inventory_snapshot  │
                                    │  order_throughput    │
                                    │  operator_prod       │
                                    │  location_util       │
                                    └─────────────────────┘
                                              │
                                              ▼
                                    Dashboard / Reports / Exports
```

### Read Models (Materialized Views)

| Read Model | Source Events | Key Columns | Refresh |
|------------|-------------|-------------|---------|
| `reporting_inventory_snapshot` | `stock-unit.state-changed`, `stock-unit.amount-changed`, `unit-load.transferred` | location_name, product_number, product_name, total_amount, reserved_amount, available_amount, lot_number, tenant_id, snapshot_date | Real-time (event-driven) |
| `reporting_order_throughput` | `delivery-order.state-changed`, `picking-order.state-changed`, `shipping-order.state-changed` | order_type, state, count, hour_bucket, day_bucket, tenant_id | Real-time (event-driven) |
| `reporting_operator_productivity` | `picking-order.state-changed`, `transport-order.state-changed` | operator_id, operator_name, picks_count, picks_per_hour, tasks_completed, shift_date, tenant_id | Real-time (event-driven) |
| `reporting_location_utilization` | `location.allocation-changed`, `unit-load.transferred` | location_name, area_name, zone_name, allocation_pct, unit_load_count, tenant_id | Real-time (event-driven) |
| `reporting_daily_inventory` | Nightly snapshot job from `reporting_inventory_snapshot` | product_number, total_amount, snapshot_date, tenant_id | Daily (batch) |

### Event Consumers

```kotlin
@ApplicationScoped
class InventoryEventConsumer(
    private val snapshotRepo: InventorySnapshotRepository,
) {
    @Incoming("karyo-inventory-stock-unit-state-changed")
    fun onStockStateChanged(event: DomainEvent<StockUnitStateChangedEvent>) {
        snapshotRepo.updateStockState(
            stockUnitId = event.payload.stockUnitId,
            newState = event.payload.newState,
            tenantId = event.tenantId,
        )
    }

    @Incoming("karyo-inventory-stock-unit-amount-changed")
    fun onStockAmountChanged(event: DomainEvent<StockUnitAmountChangedEvent>) {
        snapshotRepo.updateStockAmount(
            stockUnitId = event.payload.stockUnitId,
            newAmount = event.payload.newAmount,
            tenantId = event.tenantId,
        )
    }
}
```

### Consistency Model

Read models are **eventually consistent** with operational data:
- **Typical lag:** Seconds (Kafka consumer processing time)
- **Maximum lag during peak:** Minutes (Kafka consumer catches up after burst)
- **Acceptable for:** Analytics, dashboards, trend reports, exports
- **NOT acceptable for:** Operational decisions (stock selection, location finding — those use sync REST)

### Scope of CQRS

CQRS is applied **ONLY to reporting-service**. Other services use standard CRUD with direct database queries:

| Service | Pattern | Rationale |
|---------|---------|-----------|
| inventory-service | CRUD | High write rate, immediate consistency needed for stock operations |
| order-service | CRUD | Operational queries need current state |
| task-service | CRUD | Simple task lifecycle queries |
| product-service | CRUD | Master data, low complexity |
| layout-service | CRUD | Configuration data, low write rate |
| **reporting-service** | **CQRS** | **Cross-service analytics, eventual consistency acceptable** |
| artificial-intelligence-service | CQRS (partial) | Embeddings and predictions are separate read models |

## Consequences

### Positive
- **Cross-service analytics without coupling:** Reporting-service builds unified views across inventory, orders, tasks, and locations without accessing other services' databases or putting load on their APIs.
- **No performance impact on operations:** Reporting queries run against dedicated read models in the reporting database. A complex dashboard query cannot slow down stock selection or pick confirmation.
- **Flexible read models:** Read models are optimized for specific query patterns (pre-aggregated, denormalized, indexed for dashboard use cases). Adding a new dashboard does not require schema changes in operational services.
- **Historical data:** Daily inventory snapshots enable temporal queries ("What was our inventory level last Tuesday?") that would be impossible with live operational queries.
- **Independent scaling:** The reporting database can use read replicas, larger instance sizes, or different PostgreSQL tuning (work_mem, shared_buffers optimized for analytics) without affecting operational databases.

### Negative
- **Eventually consistent data:** Dashboard metrics may lag behind reality by seconds to minutes. Users must understand that "orders shipped today" might not include an order shipped 30 seconds ago. Mitigated by displaying "Last updated: X seconds ago" on dashboards.
- **Read model maintenance:** Each read model requires a Kafka consumer that correctly processes all relevant events and maintains the model. Bugs in consumers can cause stale or incorrect analytics. Mitigated by monitoring consumer lag and data quality checks.
- **Initial population:** When reporting-service is first deployed (or a new read model is added), there is no event history to replay (Kafka retention is 7 days). Initial population requires a one-time data migration from operational services' APIs. Mitigated by providing migration scripts that bulk-load initial data.
- **Event schema dependency:** Reporting-service consumers depend on event schemas from all producing services. Schema changes require updating reporting-service consumers. Mitigated by additive-only schema evolution and schema version field.

### Neutral
- The reporting-service database grows with aggregated event data. Partition or archive old aggregated data as needed (e.g., hourly aggregates retained for 1 year, daily aggregates retained for 5 years).
- WebSocket feeds for real-time dashboards consume from the same read models, not directly from Kafka topics.

## Alternatives Considered

### Alternative 1: Direct API Aggregation (No CQRS)
- **Pros**: Simpler architecture (no separate read models), always-consistent data (queries hit operational databases via APIs), no event consumer maintenance.
- **Cons**: Puts query load on operational services (complex reporting queries compete with stock operations), pagination complexity for large datasets, N+1 call patterns for cross-service data, slow for historical/trend reports, reporting-service availability depends on all source services being up.
- **Why rejected**: A dashboard loading "inventory by zone" would need to call inventory-service (stock data) + product-service (product names) + layout-service (zone names) for potentially thousands of rows. This creates unacceptable load on operational services during peak warehouse hours and results in slow dashboard rendering.

### Alternative 2: Direct Database Access (Shared Read Replicas)
- **Pros**: SQL JOINs across data from different services, no eventual consistency (data is always current), no event consumer maintenance, simpler implementation.
- **Cons**: Couples reporting-service to internal schemas of all other services (breaks independent evolution), read replica lag still introduces consistency issues, schema changes in any service can break reporting queries, violates database-per-service principle.
- **Why rejected**: This approach reintroduces the tight coupling that database-per-service (ADR-004) was designed to eliminate. Any schema change in inventory-service (e.g., renaming a column, adding a new index) could break reporting queries. Additionally, operational databases are not optimized for analytical queries — they use OLTP indexes and tuning, not OLAP.

## Implementation Notes
- Use Quarkus SmallRye Reactive Messaging for Kafka consumers in reporting-service.
- Read model tables are regular PostgreSQL tables (not materialized views) to allow incremental updates via event processing.
- Provide a `POST /api/internal/reporting/rebuild/{model}` endpoint for one-time initial population or recovery.
- Monitor Kafka consumer lag per read model with Prometheus alerts (alert if lag > 10,000 messages for 10 minutes).
- Daily inventory snapshot job runs at midnight warehouse-local time, capturing the state of `reporting_inventory_snapshot` into `reporting_daily_inventory`.

## Related Decisions
- [ADR-004: Database-per-Service Pattern](superseded/ADR-004-database-per-service.md)
- [ADR-005: PostgreSQL as Primary Database](ADR-005-postgresql-database.md)
- [ADR-006: Apache Kafka for Event Bus](superseded/ADR-006-kafka-event-bus.md)
- [ADR-011: Event Sourcing Decision](ADR-011-no-event-sourcing.md)

## References
- CQRS Pattern: https://microservices.io/patterns/data/cqrs.html
- Fowler, Martin. "CQRS": https://martinfowler.com/bliki/CQRS.html
- Young, Greg. "CQRS Documents": https://cqrs.files.wordpress.com/2010/11/cqrs_documents.pdf

## Revision History
- 2026-02-15: Initial version
