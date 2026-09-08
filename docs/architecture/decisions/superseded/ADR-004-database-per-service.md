# ADR-004: Database-per-Service Pattern

> **SUPERSEDED.** Superseded by [ADR-037: Modular Monolith](../ADR-037-modular-monolith.md) (2026-06).
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
Karyo WMS decomposes the monolithic myWMS application into 10 microservices (see [ADR-001](ADR-001-microservices-architecture.md)). In myWMS, all 37+ JPA entities share a single database with foreign key relationships spanning bounded contexts:

- `StockUnit` references `ItemData`, `UnitLoad`, and indirectly `StorageLocation`
- `PickingOrderLine` references `StockUnit`, `StorageLocation`, and `DeliveryOrderLine`
- `TransportOrder` references `UnitLoad` and `StorageLocation`
- `GoodsReceiptLine` references `StockUnit` and `AdviceLine`

These cross-context foreign keys create tight coupling: schema changes to `StorageLocation` in the layout context can break `StockUnit` queries in the inventory context. In a monolith this coupling is manageable through coordinated releases, but in microservices it would defeat the purpose of independent deployment.

Key considerations:
1. **Independent deployment:** Each service must evolve its schema without coordinating with other teams.
2. **Independent scaling:** inventory-service's high-write InventoryJournal should not share I/O with reporting-service's complex aggregation queries.
3. **Data isolation:** Multi-tenant data (Client/tenant) must be isolated at the database level as defense-in-depth.
4. **Edge deployment:** Each service runs its own database schema on a shared PostgreSQL instance at the edge; in the cloud, separate database instances are preferred.

## Decision
Each microservice will own a **private PostgreSQL database** (or schema, depending on deployment size). No cross-service foreign keys will exist. Cross-service data references use entity IDs only, validated at write time via synchronous REST calls.

### Database Schema Mapping

| Service | Schema | Key Tables | Estimated Annual Size |
|---------|--------|------------|----------------------|
| auth-service | `karyo_auth` | users, roles, clients, user_roles | < 100 MB |
| inventory-service | `karyo_inventory` | stock_units, unit_loads, unit_load_types, inventory_journals | 10-50 GB |
| product-service | `karyo_product` | item_data, item_units, item_data_numbers, packaging_units | < 1 GB |
| warehouse-layout-service | `karyo_layout` | storage_locations, areas, zones, location_types, fix_assignments, storage_strategies | < 500 MB |
| order-service | `karyo_orders` | delivery_orders, picking_orders, packets, shipping_orders, goods_receipts, advices, order_strategies | 5-20 GB |
| task-service | `karyo_tasks` | transport_orders, replenish_orders, stocktaking_orders | 2-10 GB |
| integration-hub | `karyo_integration` | channels, messages, webhook_subscriptions | 1-5 GB |
| reporting-service | `karyo_reporting` | report_definitions, materialized views | 5-20 GB |
| artificial-intelligence-service | `karyo_ai` | embedding_documents, predictions, query_logs | 2-10 GB |
| yard-management-service | `karyo_yard` | dock_doors, appointments, trailers | < 100 MB |

### Cross-Service References

```kotlin
// In order-service: PickingOrderLine
@Column(name = "stock_unit_id", nullable = false)
var stockUnitId: Long = 0  // References inventory-service — NO FK constraint

@Column(name = "source_location_id", nullable = false)
var sourceLocationId: Long = 0  // References layout-service — NO FK constraint
```

### Data Access Patterns

| Pattern | When | Example |
|---------|------|---------|
| **Sync REST call at write time** | Validate cross-service reference exists before persisting | Order-service validates `itemDataId` exists in product-service before creating DeliveryOrderLine |
| **Local cache for read-time enrichment** | Denormalize frequently needed cross-service data for display | Inventory-service caches product names from product-service for stock unit listing |
| **Kafka event for data sync** | Keep denormalized copies updated | Product-service publishes `item-data.updated`; inventory-service updates cached product name |
| **BFF aggregation for client queries** | Client needs data from multiple services | Mobile BFF calls inventory-service + product-service + layout-service to build a complete stock view |

### Deployment Flexibility

- **Cloud (production):** Separate PostgreSQL instances per service (e.g., RDS per service) for maximum isolation and independent scaling.
- **Cloud (smaller deployments):** Shared PostgreSQL cluster with separate schemas per service. Lower cost, still independent schemas.
- **Edge (K3s):** Single PostgreSQL instance with separate schemas. Minimizes RAM usage while maintaining logical isolation.

## Consequences

### Positive
- **Independent schema evolution:** Inventory-service can add columns to `stock_units`, partition `inventory_journals`, or change indexes without coordinating with order-service or task-service. Each service runs its own Flyway migrations independently.
- **Independent scaling:** inventory-service (high-write due to journal writes) can have a dedicated PostgreSQL instance with write-optimized settings, while product-service (read-heavy) can use read replicas. In myWMS, all queries compete for the same database connection pool.
- **Fault isolation:** A slow query in reporting-service's materialized view refresh cannot block inventory-service's stock reservation queries. Each database has its own connection pool and I/O bandwidth.
- **Multi-tenant defense-in-depth:** PostgreSQL Row-Level Security (RLS) can be applied per-schema, adding database-level tenant isolation beyond the application-level filtering.
- **Technology flexibility:** Future services could use different database technologies if justified (e.g., TimescaleDB for IoT time-series data from yard sensors), though PostgreSQL is the default.
- **Simplified backup/restore:** Each service's data can be backed up and restored independently. Restoring product master data does not require also restoring transactional inventory data.

### Negative
- **No cross-service JOINs:** Queries that myWMS performs with JOINs across contexts (e.g., "show stock with product name and location name") must now be resolved through API calls or denormalized data. This adds complexity and latency.
- **Eventual consistency:** When order-service creates a PickingOrderLine referencing a `stockUnitId`, there is a brief window where the stock unit could be deleted by inventory-service. Mitigated by the reservation pattern (reserve before pick line creation) and idempotent event handling.
- **Data duplication:** Product names, location names, and other reference data are cached/denormalized in consuming services. Storage cost is minimal, but stale data is possible if cache invalidation fails (mitigated by TTL fallback).
- **Distributed transactions:** Operations spanning multiple databases (e.g., picking saga: reserve stock in inventory-service + create pick line in order-service) require saga patterns instead of simple ACID transactions. Adds implementation complexity (see [ADR-010](../ADR-010-choreography-sagas.md)).
- **Referential integrity gaps:** Without foreign keys, orphaned references are possible (e.g., a `stockUnitId` in order-service pointing to a deleted stock unit). Mitigated by application-level validation and event-driven cleanup.
- **More database instances to manage:** In production cloud deployment, 10 separate database instances increase infrastructure management overhead. Mitigated by managed database services (RDS, Cloud SQL) and infrastructure-as-code.

### Neutral
- The myWMS data model has natural boundary points where cross-context references are limited to ID fields (e.g., `StockUnit.itemData` is used for filtering/display but not for inventory calculations). This makes the decomposition cleaner than in more tightly coupled domains.
- Flyway migration numbering is per-service (`V1__` in inventory-service is independent of `V1__` in order-service), simplifying migration management.

## Alternatives Considered

### Alternative 1: Shared Database with Schema Isolation
- **Pros**: Cross-service JOINs possible (views across schemas), simpler infrastructure (one DB instance), PostgreSQL's schema isolation provides some boundaries, standard ACID transactions across schemas, familiar to developers from monolithic background.
- **Cons**: Schema changes in one service can still impact shared views, services compete for the same connection pool and I/O, cannot independently scale databases, deployment coordination still needed if views span schemas, less fault isolation.
- **Why rejected**: While simpler to operate, shared database access creates implicit coupling that undermines independent deployment. A slow reporting query in a shared schema can impact inventory operations. The edge deployment (single PostgreSQL instance) effectively uses this pattern, but with strict discipline: no cross-schema queries, only per-schema access. In cloud production, separate instances are preferred for isolation and scaling.

### Alternative 2: Polyglot Persistence
- **Pros**: Each service uses the optimal database for its workload (PostgreSQL for transactions, MongoDB for documents, Elasticsearch for search, TimescaleDB for time-series, Redis for session state).
- **Cons**: Operational complexity (managing 4-5 different database technologies), increased skill requirements for the team, more complex backup and disaster recovery, harder to maintain consistency across different database paradigms, violates edge deployment simplicity.
- **Why rejected**: PostgreSQL 16+ with its extensions (pgvector for AI, JSONB for flexible schemas, table partitioning for journals) covers all current Karyo requirements without introducing multiple database technologies. The operational simplicity of a single database technology across all services outweighs the marginal performance benefits of purpose-built databases. If a compelling case emerges later (e.g., Elasticsearch for complex warehouse search), it can be introduced for a specific service without changing the overall pattern.

## Implementation Notes
- **Edge deployment:** Use a single PostgreSQL instance with separate schemas (`CREATE SCHEMA karyo_inventory; CREATE SCHEMA karyo_orders;`). Each service connects with a user that has access only to its own schema.
- **Cloud deployment:** Use separate PostgreSQL instances (RDS, Cloud SQL) per service, or at minimum per service group (e.g., inventory + product on one instance, orders + tasks on another).
- **Connection pooling:** Quarkus Agroal built-in connection pool per service. Default: min 2, max 20 connections. Tune per service based on workload.
- **Cross-service ID validation:** Use Quarkus REST Client to validate IDs at write time. Cache validation results (Caffeine L1, 5 min TTL) to avoid repeated calls for the same ID.
- **Outbox table:** Each service database includes an `outbox` table for reliable event publishing (see [ADR-006](ADR-006-kafka-event-bus.md)).

## Related Decisions
- [ADR-001: Microservices Architecture Style](ADR-001-microservices-architecture.md)
- [ADR-005: PostgreSQL as Primary Database](../ADR-005-postgresql-database.md)
- [ADR-006: Apache Kafka for Event Bus](ADR-006-kafka-event-bus.md)
- [ADR-010: Event-Driven Architecture with Choreography-Based Sagas](../ADR-010-choreography-sagas.md)
- [ADR-011: Event Sourcing Decision](../ADR-011-no-event-sourcing.md)
- [ADR-014: Flyway for Database Migrations](../ADR-014-flyway-migrations.md)

## References
- Richardson, Chris. "Microservices Patterns" — Chapter 4: Managing transactions with sagas
- Database per Service pattern: https://microservices.io/patterns/data/database-per-service.html
- Quarkus Agroal (connection pooling): https://quarkus.io/guides/datasource
- PostgreSQL schemas: https://www.postgresql.org/docs/16/ddl-schemas.html

## Revision History
- 2026-02-15: Initial version
