# ADR-011: Event Sourcing Decision -- Traditional CRUD with Audit Journal

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md) — Still holds — but for a different reason.
>
> The **conclusion stands**: Karyo uses CRUD plus an `InventoryJournal` audit trail, not event sourcing.
> The *stated* rationale ("Karyo already uses Kafka events for inter-service communication, suggesting
> event sourcing is a natural fit") is void — there is no Kafka. The real argument today is simpler:
> service operations use in-process transactions. The journal and active `outbox_events` relay
> retain audit/integration facts; they do not provide arbitrary historical-state replay.


## Status
Partially superseded

## Context
Event sourcing is an architectural pattern where state changes are stored as a sequence of immutable events, and the current state is derived by replaying those events. It provides a complete audit trail, enables temporal queries ("what was the stock at 3pm yesterday?"), and supports building multiple read projections from the same event stream.

For Karyo WMS, event sourcing was considered because:

1. **Audit requirements:** Warehouse operations require full traceability of every stock change (who moved what, when, where, and why). Regulatory requirements (FDA 21 CFR Part 11 for pharma, food safety regulations) may demand complete audit trails.
2. **Temporal queries:** "What was the inventory snapshot at the end of last shift?" or "What was the stock level when this order was picked?" are valuable for dispute resolution and analytics.
3. **Event replay:** Rebuilding read models or migrating data could benefit from replaying the full event history.
4. **Natural event stream:** Karyo already uses Kafka events for inter-service communication, suggesting event sourcing could be a natural fit.

However, several factors argue against event sourcing:

1. **myWMS uses CRUD + Journal:** myWMS already implements a proven CRUD model with an `InventoryJournal` audit trail. Every stock change writes a journal entry with denormalized snapshots (product number, location names, amounts). This pattern has been proven in production warehouse operations.
2. **Query complexity:** Stock selection (13-pass algorithm) requires complex queries against current state with joins, window functions, and ordering. Event-sourced projections would need to maintain these materialized views in real-time, adding significant complexity.
3. **Consistency requirements:** Inventory operations require immediate consistency — an operator scanning a product needs the current stock level NOW, not an eventually-consistent projection that might lag behind.
4. **Team experience:** Event sourcing requires a fundamentally different mental model (events as source of truth, CQRS everywhere, projection management, snapshot optimization). The Phase 0 team's WMS domain expertise is more valuable than introducing a new architectural paradigm.

## Decision
We will **NOT use event sourcing** for any Karyo WMS service. Instead, we will use **traditional CRUD** (Hibernate + PostgreSQL) with the **InventoryJournal as an immutable audit trail**, preserving the proven myWMS pattern.

### Architecture

```
┌─────────────────────────────────────────────────┐
│                   Service                        │
│                                                  │
│  ┌──────────┐    ┌──────────┐   ┌────────────┐ │
│  │ Business │───►│ Entity   │──►│ PostgreSQL │ │
│  │  Logic   │    │ (CRUD)   │   │ (source of │ │
│  │          │    │          │   │   truth)   │ │
│  └────┬─────┘    └──────────┘   └────────────┘ │
│       │                                          │
│       ├──────────────────────────────────────┐   │
│       ▼                                      ▼   │
│  ┌──────────┐                        ┌──────────┐│
│  │ Inventory│                        │  Outbox  ││
│  │ Journal  │                        │  Table   ││
│  │(immutable│                        │ (events) ││
│  │ audit)   │                        └────┬─────┘│
│  └──────────┘                             │      │
│                                           ▼      │
│                                    ┌──────────┐  │
│                                    │  Kafka   │  │
│                                    │ (notify) │  │
│                                    └──────────┘  │
└─────────────────────────────────────────────────┘
```

**Key principle:** PostgreSQL is the source of truth. Kafka events are notifications, NOT the source of truth. The InventoryJournal is an audit trail, NOT an event store for state reconstruction.

### InventoryJournal Pattern

```kotlin
@Entity
@Table(name = "inventory_journals")
class InventoryJournal : TenantEntity() {
    // ALL columns are updatable = false — immutable after creation
    @Column(updatable = false)
    var recordType: Int = 0  // CREATED, CHANGED, PICKED, TRANSFERRED, COUNTED, DELETED

    @Column(updatable = false, precision = 17, scale = 4)
    var amount: BigDecimal? = null

    @Column(updatable = false, precision = 17, scale = 4)
    var stockUnitAmount: BigDecimal? = null  // Amount after change

    @Column(updatable = false)
    var fromUnitLoad: String? = null

    @Column(updatable = false)
    var toUnitLoad: String? = null

    @Column(updatable = false)
    var fromStorageLocation: String? = null

    @Column(updatable = false)
    var toStorageLocation: String? = null

    @Column(updatable = false)
    var activityCode: String? = null

    @Column(updatable = false)
    var productNumber: String? = null

    @Column(updatable = false)
    var productName: String? = null

    @Column(updatable = false)
    var lotNumber: String? = null

    @Column(updatable = false)
    var operatorName: String? = null

    @Column(updatable = false)
    var correlationId: String? = null  // NEW: cross-service tracing
}
```

**Record Types:**

| Type | Code | When Written |
|------|------|-------------|
| CREATED | 1 | New stock unit created (goods receipt) |
| CHANGED | 2 | Amount adjusted (manual correction, damage) |
| PICKED | 3 | Stock picked for order |
| TRANSFERRED | 5 | Stock moved between unit loads |
| COUNTED | 7 | Stocktaking count recorded |
| DELETED | 8 | Stock unit removed |

The journal stores **denormalized snapshots** (product number, product name, location names, amounts) so it is self-contained for audit queries without requiring joins to current entity state. This means the journal remains accurate even if the product name changes or a location is renamed after the stock operation.

### What We Gain over myWMS

While preserving the CRUD + Journal pattern, Karyo adds:
- **correlationId:** Every journal entry includes the correlation ID from the originating request, enabling cross-service audit trail tracing
- **Table partitioning:** Journal partitioned by month (see [ADR-005](ADR-005-postgresql-database.md)), enabling efficient queries and archival. **Not implemented as decided (verified 2026-08-28):** `V104__create_inventory_journals.sql` declares `PARTITION BY RANGE (created)` and creates a single `inventory_journals_default` DEFAULT partition, and nothing anywhere creates monthly partitions - no further `PARTITION OF`, no `pg_partman`, no scheduled DDL job. Every row written to date lives in the default partition. What shipped is the table *shape* that makes monthly partitions addable later without a rewrite; it is structural preparation, not an active retention or pruning mechanism.
- **Kafka event stream:** In addition to the journal, Kafka events feed reporting-service and artificial-intelligence-service for real-time analytics (journal is for audit, Kafka is for integration)

## Consequences

### Positive
- **Proven pattern:** The CRUD + Journal pattern is exactly what myWMS uses in production. It is well-understood, thoroughly tested in warehouse operations, and handles regulatory audit requirements.
- **Simple query model:** Stock selection, location finding, and order state calculation query current state directly from PostgreSQL with standard SQL. No projection management, no eventual consistency for operational queries.
- **Lower complexity:** No event store, no projection rebuilding, no snapshotting strategy, no event versioning for state reconstruction. The team focuses on domain logic, not event sourcing infrastructure.
- **Immediate consistency:** An operator who just moved stock sees the updated location immediately. No "projection lag" that could cause a pick from the wrong location.
- **Straightforward debugging:** Current state is in the database. "What is the stock level?" requires a single SQL query, not replaying an event stream.
- **Better performance for writes:** CRUD writes update one row. Event sourcing writes an event + potentially rebuilds projections. For a WMS with high write rates (10K+ stock operations/hour), the simpler write path matters.

### Negative
- **No built-in temporal queries:** "What was the stock at 3pm?" requires querying the InventoryJournal and reconstructing state from journal entries — more complex than event sourcing's natural replay. Mitigated by the reporting-service maintaining daily inventory snapshots as materialized views.
- **Journal is write-only, not replay-capable:** Unlike an event store, the InventoryJournal captures changes but cannot reconstruct entity state purely from journal entries (it stores deltas and snapshots, not state transitions). Full state reconstruction requires combining journal entries with current state.
- **Dual write (entity + journal):** Every stock operation writes to both the entity table and the journal table within the same transaction. This is a minor performance overhead but adds write amplification. Mitigated by PostgreSQL's efficient WAL-based writes and the journal table being append-only.
- **Cannot rebuild read models from events alone:** If reporting-service needs a new read model, it must be populated from current database state + Kafka event replay (limited by retention), not from a complete event history. Kafka topic retention (7 days) limits replay window.

### Neutral
- The InventoryJournal table will grow large (millions of rows per year for a mid-volume warehouse). Table partitioning by month and automated partition management (pg_partman) handle this efficiently.
- Kafka events serve as the "event stream" for cross-service integration. They carry similar information to what an event-sourced system would publish, but are not the source of truth.

## Alternatives Considered

### Alternative 1: Full Event Sourcing (EventStoreDB or Kafka as Event Store)
- **Pros**: Complete audit trail with temporal queries by default, natural replay for building new read models, no impedance mismatch between events and event bus (events ARE the data), supports complex event-driven workflows.
- **Cons**: Requires CQRS everywhere (current state is a projection, not a table), projection management is complex (rebuilding projections for 10+ services), eventual consistency for all read paths (unacceptable for stock selection), event versioning and schema evolution for state reconstruction is significantly more complex than Kafka event versioning for notifications, requires specialized database (EventStoreDB) or Kafka retention forever (storage cost), team must learn a fundamentally different data model.
- **Why rejected**: The core issue is that WMS operational queries (stock selection, location finding) require immediate consistency and complex SQL (joins, window functions, CTEs, partial indexes). Maintaining these as eventually-consistent projections adds massive complexity without clear benefit. The InventoryJournal + Kafka events provide sufficient audit trail and cross-service integration without the event sourcing overhead. May be reconsidered for specific bounded contexts (e.g., full order history replay) in Phase 3+ if a compelling use case emerges.

### Alternative 2: Hybrid Event Sourcing (Event-Sourced Inventory, CRUD Everything Else)
- **Pros**: Event sourcing where audit matters most (inventory), simpler CRUD for less audit-sensitive services (product master, layout), best of both worlds.
- **Cons**: Two different data patterns across services increases cognitive load, inventory service is the most complex service (13-pass stock selection) — event-sourcing it makes the hardest service even harder, inconsistent patterns across services make testing and tooling more complex.
- **Why rejected**: Inventory-service is the wrong service to event-source. It has the most complex query patterns (PickingStockFinder with 13 passes, each using JPQL with window functions and ordering), the highest write rate (every stock operation), and the strongest consistency requirements (stock availability must be immediately accurate). Event sourcing would make the hardest service harder. The InventoryJournal already provides the audit trail that motivates event sourcing for this context.

## Implementation Notes
- Every `StockService` method that modifies stock must call `JournalService.record()` within the same `@Transactional` boundary.
- Journal entries are immutable — the entity uses `@Column(updatable = false)` on all columns.
- Partition `inventory_journals` by month using PostgreSQL range partitioning (see [ADR-005](ADR-005-postgresql-database.md)). **Partly done:** the table is range-partitioned on `created`, but only a DEFAULT partition exists (2026-08-28).
- Archive partitions older than the regulatory retention period (configurable per customer - typically 5-10 years for pharma, 3 years for general warehouse). **Not done:** no partitions to archive, no archival job.
- The reporting-service builds daily `inventory_snapshot` materialized views from Kafka events for temporal queries.

## Related Decisions
- [ADR-004: Database-per-Service Pattern](superseded/ADR-004-database-per-service.md)
- [ADR-005: PostgreSQL as Primary Database](ADR-005-postgresql-database.md)
- [ADR-006: Apache Kafka for Event Bus](superseded/ADR-006-kafka-event-bus.md)
- [ADR-010: Event-Driven Architecture with Choreography-Based Sagas](ADR-010-choreography-sagas.md)
- [ADR-012: CQRS for Reporting Service](ADR-012-cqrs-reporting.md)

## References
- Fowler, Martin. "Event Sourcing": https://martinfowler.com/eaaDev/EventSourcing.html
- Current inventory behavior: [Inventory Operations](../../functional/inventory-operations.md)
- Journal contract: stock changes record immutable, denormalized snapshots in `InventoryJournal`

## Revision History
- 2026-02-15: Initial version
- 2026-08-28: Annotated the two partitioning statements in place - the range-partitioned table shape shipped, the monthly partitions and the archival job did not. Decision unchanged.
