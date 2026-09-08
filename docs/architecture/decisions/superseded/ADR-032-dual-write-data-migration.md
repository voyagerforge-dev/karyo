# ADR-032: Dual-Write Pattern for Data Migration

> **SUPERSEDED.** Superseded by [ADR-037: Modular Monolith](../ADR-037-modular-monolith.md) (2026-06).
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
During the Strangler Fig migration (ADR-031), both myWMS and Karyo must maintain consistent views of warehouse data. When a service is migrated to Karyo, data must flow between systems to ensure:

- **Karyo has current data**: Before becoming primary, Karyo must have a complete and current copy of all relevant data from myWMS
- **myWMS stays current**: While myWMS serves as fallback, it must reflect any writes that go through Karyo
- **Consistency verification**: There must be a mechanism to detect and report data discrepancies between systems
- **Rollback safety**: If Karyo is rolled back, myWMS data must be complete enough to resume operations immediately

The data migration challenge is compounded by the fact that warehouse data is highly stateful and continuously changing — stock levels, order states, and task statuses change thousands of times per hour during peak operations.

## Decision
We will implement a **dual-write pattern** with **Change Data Capture (CDC) via Debezium** as the primary data synchronization mechanism between myWMS and Karyo during the migration transition.

**Architecture:**

```
┌─────────────────────────────────────────────────────────────┐
│                  During Migration Transition                  │
│                                                               │
│  ┌──────────┐                           ┌──────────┐        │
│  │  myWMS   │                           │  Karyo   │        │
│  │ Database │                           │ Services │        │
│  │(PostgreSQL)│                          │          │        │
│  └─────┬─────┘                          └─────┬────┘        │
│        │                                      │             │
│        │ WAL                                  │ writes      │
│        ▼                                      ▼             │
│  ┌──────────┐    Kafka Topics     ┌──────────────┐          │
│  │ Debezium │───────────────────►│  Karyo CDC   │          │
│  │ Connector│  cdc.mywms.*       │  Consumer    │          │
│  └──────────┘                    └──────────────┘          │
│                                                             │
│  ┌──────────────┐  Kafka Topics  ┌──────────────┐          │
│  │  myWMS CDC   │◄──────────────│  Karyo Event │          │
│  │  Consumer    │  karyo.*       │  Producer    │          │
│  │  (write-back)│               └──────────────┘          │
│  └──────────────┘                                          │
│                                                             │
│  ┌──────────────────────────────────────────────┐          │
│  │           Reconciliation Service              │          │
│  │  Nightly: compare key entities between        │          │
│  │  myWMS and Karyo, report discrepancies       │          │
│  └──────────────────────────────────────────────┘          │
└─────────────────────────────────────────────────────────────┘
```

**Phase 1: Initial Data Load (Before Migration)**

Before any service migration, perform a full data snapshot from myWMS to Karyo:

```
1. Snapshot myWMS database (pg_dump or logical replication snapshot)
2. Transform data to Karyo schema (ETL script per service)
3. Load into Karyo service databases
4. Start CDC streaming from snapshot LSN position
5. Verify row counts and checksums match
```

**Phase 2: CDC from myWMS to Karyo (myWMS is primary)**

Debezium captures every change from the myWMS PostgreSQL WAL and publishes to Kafka:

```
myWMS PostgreSQL WAL
    │
    ▼
Debezium PostgreSQL Connector
    │
    │  Publishes to Kafka topics:
    │  cdc.mywms.public.stock_units
    │  cdc.mywms.public.unit_loads
    │  cdc.mywms.public.delivery_orders
    │  cdc.mywms.public.picking_orders
    │  ...
    │
    ▼
Karyo CDC Consumer Service
    │
    │  Transforms myWMS schema → Karyo schema
    │  Applies the entity-to-service mapping
    │  Writes to appropriate Karyo service database
    │
    ▼
Karyo Service Databases (karyo_inventory, karyo_orders, ...)
```

**Debezium Connector Configuration:**

```json
{
  "name": "mywms-cdc-connector",
  "config": {
    "connector.class": "io.debezium.connector.postgresql.PostgresConnector",
    "database.hostname": "mywms-db",
    "database.port": "5432",
    "database.user": "debezium",
    "database.password": "${DEBEZIUM_PASSWORD}",
    "database.dbname": "mywms",
    "database.server.name": "mywms",
    "plugin.name": "pgoutput",
    "slot.name": "karyo_migration",
    "table.include.list": "public.stock_units,public.unit_loads,public.unit_load_types,public.item_data,public.item_units,public.storage_locations,public.areas,public.location_types,public.delivery_orders,public.delivery_order_lines,public.picking_orders,public.picking_order_lines,public.transport_orders,public.replenish_orders,public.goods_receipts,public.advices",
    "transforms": "route",
    "transforms.route.type": "io.debezium.transforms.ByLogicalTableRouter",
    "transforms.route.topic.regex": "mywms\\.public\\.(.*)",
    "transforms.route.topic.replacement": "cdc.mywms.$1",
    "key.converter": "org.apache.kafka.connect.json.JsonConverter",
    "value.converter": "org.apache.kafka.connect.json.JsonConverter",
    "snapshot.mode": "initial"
  }
}
```

**Phase 3: Dual-Write (Karyo becomes primary)**

When Karyo becomes primary for a service, writes go to Karyo first, then replicate back to myWMS:

```kotlin
// Karyo write path (primary)
@ApplicationScoped
class DualWriteStockService(
    private val stockRepo: StockUnitRepository,    // Karyo DB
    private val eventProducer: InventoryEventProducer,  // Kafka
    private val migrationConfig: MigrationConfig,
) {
    @Transactional
    fun reserveStock(stockUnitId: Long, amount: BigDecimal): PickResult {
        // 1. Write to Karyo (primary)
        val result = doReserve(stockUnitId, amount)

        // 2. Emit event for myWMS write-back (if dual-write enabled)
        if (migrationConfig.isDualWriteEnabled("inventory")) {
            eventProducer.emitForLegacySync(StockReservedEvent(stockUnitId, amount))
        }

        return result
    }
}

// myWMS write-back consumer
@ApplicationScoped
class LegacyWriteBackConsumer {
    @Incoming("karyo-to-mywms-sync")
    fun handleKaryoEvent(event: DomainEvent<*>) {
        // Transform Karyo event to myWMS write
        // Apply idempotently (check if already applied via event ID)
        legacyDao.applyChange(event)
    }
}
```

**Schema Transformation:**

```kotlin
// CDC Consumer transforms myWMS schema to Karyo schema
class MywmsToKaryoTransformer {

    fun transformStockUnit(cdcRecord: CdcRecord): StockUnitEntity {
        return StockUnitEntity(
            // myWMS column → Karyo column mapping
            id = cdcRecord.getLong("id"),
            tenantId = cdcRecord.getLong("client_id"),  // client_id → tenant_id
            itemDataId = cdcRecord.getLong("item_data_id"),
            amount = cdcRecord.getBigDecimal("amount"),
            reservedAmount = cdcRecord.getBigDecimal("reserved_amount"),
            state = cdcRecord.getInt("state"),
            lockType = cdcRecord.getInt("lock"),  // lock → lock_type
            lotNumber = cdcRecord.getString("lot_number"),
            serialNumber = cdcRecord.getString("serial_number"),
            bestBefore = cdcRecord.getLocalDate("best_before"),
            strategyDate = cdcRecord.getInstant("strategy_date"),
            unitLoadId = cdcRecord.getLong("unit_load_id"),
            created = cdcRecord.getInstant("created"),
            modified = cdcRecord.getInstant("modified"),
            version = cdcRecord.getInt("version"),
        )
    }
}
```

**Reconciliation Service:**

```kotlin
@ApplicationScoped
@Scheduled(cron = "0 0 2 * * ?")  // Run at 2 AM daily
class DataReconciliationService(
    private val karyoStockRepo: StockUnitRepository,
    private val mywmsClient: MywmsDataClient,  // REST or direct DB read
    private val alertService: AlertService,
) {
    fun reconcileStockUnits() {
        val karyoStocks = karyoStockRepo.listAll()
            .associate { it.id!! to it.amount }

        val mywmsStocks = mywmsClient.getAllStockAmounts()
            .associate { it.id to it.amount }

        val discrepancies = mutableListOf<Discrepancy>()

        // Check for amount mismatches
        for ((id, karyoAmount) in karyoStocks) {
            val mywmsAmount = mywmsStocks[id]
            if (mywmsAmount == null) {
                discrepancies.add(Discrepancy(id, "MISSING_IN_MYWMS", karyoAmount, null))
            } else if (karyoAmount.compareTo(mywmsAmount) != 0) {
                discrepancies.add(Discrepancy(id, "AMOUNT_MISMATCH", karyoAmount, mywmsAmount))
            }
        }

        // Check for records in myWMS not in Karyo
        for ((id, _) in mywmsStocks) {
            if (id !in karyoStocks) {
                discrepancies.add(Discrepancy(id, "MISSING_IN_KARYO", null, mywmsStocks[id]))
            }
        }

        if (discrepancies.isNotEmpty()) {
            logger.warn("Reconciliation found ${discrepancies.size} discrepancies")
            alertService.reportDiscrepancies(discrepancies)
            metricsRecorder.recordDiscrepancies("stock_units", discrepancies.size)
        }
    }
}
```

**Data Validation Approach:**

| Entity | Validation Method | Frequency | Tolerance |
|--------|-----------------|-----------|-----------|
| Stock amounts | Sum by item + location | Nightly | 0 discrepancies |
| Order states | Compare state values | Nightly | 0 discrepancies |
| Unit load locations | Compare location references | Nightly | 0 discrepancies |
| Product master data | Hash comparison | Weekly | 0 discrepancies (read-only data) |
| Journal entries | Count comparison | Nightly | < 0.01% difference |

**Rollback Procedure:**

If Karyo must be rolled back to myWMS:
1. Switch feature flag: `write_target: "mywms"`, `read_source: "mywms"` (immediate, < 1 second)
2. myWMS database is current (dual-write kept it updated)
3. Stop Karyo CDC consumers
4. Investigate and fix the issue in Karyo
5. Re-sync Karyo database from myWMS via Debezium snapshot
6. Resume migration

## Consequences

### Positive
- CDC via Debezium is non-intrusive to myWMS — reads the PostgreSQL WAL without modifying the application or adding triggers
- Near-real-time data synchronization (CDC lag typically < 1 second) — both systems stay closely in sync
- Kafka as the intermediary provides buffering, replay capability, and exactly-once semantics
- Reconciliation service provides confidence in data consistency with automated discrepancy detection
- Rollback is safe — dual-write ensures myWMS always has current data
- Debezium captures all changes including those made by myWMS batch jobs, direct SQL updates, and stored procedures (anything that hits the WAL)
- Schema transformation is centralized in the CDC consumer — the mapping from myWMS entities to Karyo services is explicit and testable

### Negative
- Dual-write adds complexity and potential for inconsistency — if one write succeeds and the other fails, the systems diverge
- CDC lag (even < 1 second) means there are brief windows of inconsistency between systems
- Debezium requires a PostgreSQL replication slot which consumes WAL storage — must be monitored to prevent disk exhaustion if the consumer falls behind
- Schema transformation logic must be maintained as both systems evolve during the migration period
- Reconciliation of eventually-consistent data is complex — timestamp-based comparison must account for propagation delays
- Increased Kafka throughput during dual-write — all myWMS changes plus all Karyo changes flow through Kafka simultaneously

### Neutral
- The dual-write period has a defined end — once myWMS is decommissioned (ADR-031 Phase 5), all CDC infrastructure is removed
- Debezium is a mature CNCF project with excellent PostgreSQL support and wide production adoption
- The reconciliation service can evolve into a permanent data quality monitoring tool after migration completes

## Alternatives Considered

### Alternative 1: Offline Batch Migration
- **Pros**: Simplest approach — export from myWMS, transform, import to Karyo during a maintenance window. No dual-write complexity, no CDC infrastructure, clean cutover.
- **Cons**: Requires extended downtime for data migration (hours for large databases). No rollback once myWMS database diverges from production state. All-or-nothing — no gradual migration. Warehouse operations must halt during migration window. Not compatible with 24/7 operations requirement.
- **Why rejected**: Incompatible with the zero-downtime requirement. A warehouse processing 10K+ orders/hour cannot afford hours of downtime for data migration. Even with a planned maintenance window, the risk of a failed migration requiring a lengthy rollback is unacceptable.

### Alternative 2: CDC Only (No Dual-Write Back)
- **Pros**: Simpler architecture — data flows one-way from myWMS to Karyo (or from Karyo to myWMS), not both directions. Avoids circular replication complexity. Easier to reason about data flow.
- **Cons**: No rollback safety — if Karyo is primary and myWMS is not being updated, rolling back to myWMS means losing all changes since the last sync. This defeats the purpose of maintaining myWMS as a fallback. The safety net is only as good as the last successful replication.
- **Why rejected**: Rollback safety is a core requirement. During the migration transition, myWMS must be a viable fallback at all times. One-way CDC from Karyo to myWMS does not update myWMS for changes that originate in Karyo. Dual-write ensures both systems are always current.

### Alternative 3: Export/Import Scripts (Manual ETL)
- **Pros**: No infrastructure dependency (no Debezium, no Kafka for CDC). Simple scripts that can be run on demand. Full control over transformation logic.
- **Cons**: Manual process prone to human error. Not real-time — data freshness depends on script execution frequency. Difficult to handle incremental changes (must track "what changed since last run"). Does not capture deletes or updates that revert to previous values. No built-in exactly-once guarantees.
- **Why rejected**: Manual ETL does not scale to the volume and frequency of changes in a high-throughput warehouse. Running export/import scripts hourly would miss real-time data, while running them more frequently creates performance overhead on both systems. CDC via Debezium provides automatic, real-time capture of all changes with exactly-once delivery.

## Implementation Notes
- Deploy Debezium as a Kafka Connect connector in the Kubernetes cluster; use the Strimzi Kafka operator for Kafka Connect management
- Create a dedicated PostgreSQL replication slot for Debezium with a logical decoding plugin (`pgoutput`); monitor slot lag via Prometheus metrics
- Implement the CDC consumer as a dedicated Quarkus service (`migration-sync-service`) that handles transformation and writing to Karyo databases
- Use Kafka consumer group offsets for exactly-once processing; implement idempotent writes (check for existing records before insert, compare versions before update)
- Implement dead-letter queue for CDC events that fail transformation — these should be investigated, not silently dropped
- Build the reconciliation service with configurable entity sets and validation rules; start with stock amounts (most critical) and expand to other entities
- Set up Grafana dashboards for migration monitoring: CDC lag, dual-write latency, reconciliation discrepancy counts, events processed per second
- Create migration runbook documenting: initial data load procedure, Debezium connector setup, CDC consumer startup, reconciliation verification, rollback procedure
- Test the full migration pipeline in staging with production-like data volumes before attempting production migration
- Implement circuit breakers on the dual-write path — if myWMS write-back fails, log the failure and continue (Karyo is primary); reconciliation will catch the discrepancy

## Related Decisions
- [ADR-031](ADR-031-strangler-fig-migration.md): Strangler Fig Pattern — defines the overall migration strategy that dual-write supports
- [ADR-006](ADR-006-kafka-event-bus.md): Apache Kafka for Event Bus — CDC events flow through the same Kafka infrastructure
- [ADR-020](../ADR-020-multi-tenancy-strategy.md): Multi-Tenancy — CDC transformation must map myWMS `client_id` to Karyo `tenant_id`
- [ADR-023](../ADR-023-prometheus-grafana-observability.md): Prometheus + Grafana — migration monitoring dashboards

## References
- [Debezium Documentation](https://debezium.io/documentation/)
- [Debezium PostgreSQL Connector](https://debezium.io/documentation/reference/connectors/postgresql.html)
- [Change Data Capture with Debezium (Confluent)](https://www.confluent.io/blog/how-change-data-capture-works-patterns-solutions-implementation/)
- [Martin Fowler: Data Migration Patterns](https://martinfowler.com/articles/patterns-of-distributed-systems/)
- [Exactly-Once Semantics in Kafka](https://www.confluent.io/blog/exactly-once-semantics-are-possible-heres-how-apache-kafka-does-it/)

## Revision History
- 2026-02-15: Initial version
