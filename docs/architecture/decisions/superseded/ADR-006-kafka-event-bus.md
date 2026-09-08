# ADR-006: Apache Kafka for Event Bus

> **SUPERSEDED.** Superseded by [ADR-037: Modular Monolith](../ADR-037-modular-monolith.md) (2026-06).
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
Karyo WMS uses an event-driven architecture (see [ADR-001](ADR-001-microservices-architecture.md)) where 10 microservices communicate asynchronously through domain events. In the legacy myWMS monolith, cross-concern communication uses CDI events — 16 distinct event types firing synchronously within JTA transactions. In Karyo, these become asynchronous events for state change propagation, analytics feeds, and cross-service notifications.

Key requirements for the event bus:

1. **Durability and replay:** Events are the communication backbone for eventual consistency. If reporting-service goes down, it must be able to replay missed events when it recovers. This rules out fire-and-forget messaging.
2. **Ordering guarantees:** Stock operations for the same tenant must be processed in order to maintain consistency (e.g., stock reserved before stock picked). Per-tenant ordering is required.
3. **Exactly-once semantics:** Stock amount changes and order state transitions must not be double-processed. At-least-once delivery with idempotent consumers is the minimum; exactly-once is preferred.
4. **High throughput:** At 10K orders/hour with 3-5 events per order, the system must handle 30-50K events/hour sustained, with peaks of 100K+/hour during wave releases.
5. **Multi-consumer:** The same event (e.g., `stock-unit.state-changed`) is consumed by order-service (for state tracking), reporting-service (for analytics), and artificial-intelligence-service (for anomaly detection). Each consumer processes independently.
6. **Edge deployment:** The event bus must run on K3s with < 256 MB RAM at edge sites.
7. **Schema evolution:** Event schemas must evolve (add fields) without breaking existing consumers.

## Decision
We will use **Apache Kafka 3.x** as the event bus for all asynchronous inter-service communication.

### Topic Design

**Naming convention:** `karyo.{service}.{entity}.{event-type}`

| Topic | Partition Key | Partitions | Retention | Key Consumers |
|-------|--------------|------------|-----------|---------------|
| `karyo.inventory.stock-unit.state-changed` | clientId | 12 | 7 days | order-service, reporting-service |
| `karyo.inventory.stock-unit.amount-changed` | clientId | 12 | 7 days | reporting-service, artificial-intelligence-service |
| `karyo.inventory.unit-load.transferred` | clientId | 12 | 7 days | task-service, reporting-service |
| `karyo.inventory.lock.changed` | clientId | 6 | 7 days | order-service |
| `karyo.orders.delivery-order.state-changed` | clientId | 12 | 7 days | integration-hub, reporting-service |
| `karyo.orders.picking-order.state-changed` | clientId | 12 | 7 days | task-service |
| `karyo.orders.shipping-order.state-changed` | clientId | 6 | 7 days | integration-hub |
| `karyo.orders.goods-receipt.state-changed` | clientId | 6 | 7 days | task-service, integration-hub |
| `karyo.tasks.transport-order.state-changed` | clientId | 12 | 7 days | inventory-service |
| `karyo.tasks.transport-order.completed` | clientId | 12 | 7 days | inventory-service, layout-service |
| `karyo.tasks.stocktaking.completed` | clientId | 6 | 7 days | inventory-service |
| `karyo.layout.fix-assignment.threshold-breached` | clientId | 6 | 3 days | task-service |
| `karyo.integration.order.received` | clientId | 6 | 7 days | order-service |
| `karyo.integration.product.synced` | clientId | 6 | 7 days | product-service |

### Event Envelope

```kotlin
data class DomainEvent<T>(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String,
    val timestamp: Instant = Instant.now(),
    val source: String,
    val correlationId: String? = null,
    val tenantId: Long,
    val schemaVersion: Int = 1,
    val payload: T,
)
```

### Event Schema Strategy

- **Format:** JSON with JSON Schema validation (not Avro)
- **Why JSON over Avro:** Simpler for open-source contributors, human-readable for debugging, no schema registry infrastructure required. The trade-off is larger message size and no native schema evolution tooling — mitigated by explicit `schemaVersion` field and additive-only changes.
- **Evolution rules:** Additive changes only (new optional fields with defaults) within the same major version. Breaking changes require a new topic version (`*.v2`).

### Outbox Pattern

Services write events to a local `outbox` table within the business transaction, then a relay (Debezium or polling) publishes them to Kafka:

```sql
CREATE TABLE outbox (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    aggregate_type VARCHAR(100) NOT NULL,
    aggregate_id VARCHAR(100) NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    payload JSONB NOT NULL,
    created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    published BOOLEAN NOT NULL DEFAULT FALSE
);
```

This prevents dual-write inconsistency (DB committed but Kafka publish failed, or vice versa).

### Dead Letter Queue

Each consumer group has a DLQ topic: `karyo.{service}.dlq`
- Failed messages retried 3 times with exponential backoff (1s, 5s, 30s)
- After exhaustion, message routed to DLQ with failure metadata (exception, stack trace, attempt count)
- DLQ monitoring via Prometheus alerts (`karyo_dlq_messages_total` counter)
- Manual replay tooling for DLQ investigation

### Edge Deployment

At edge sites, **Redpanda** (Kafka-compatible API) replaces Apache Kafka:
- Single-node deployment, < 256 MB RAM
- No JVM required (C++ implementation)
- Wire-compatible with Kafka clients — no code changes
- Kafka MirrorMaker 2 or Redpanda's built-in replication syncs events to cloud Kafka

## Consequences

### Positive
- **Durability and replay:** Kafka's log-based storage ensures events are retained for the configured retention period (7 days default). A reporting-service outage lasting hours or days can catch up by replaying from its last committed offset.
- **Per-tenant ordering:** Partitioning by `clientId` ensures all events for a tenant are processed in order within a partition. This maintains stock operation ordering per tenant without requiring total ordering across all tenants.
- **Multi-consumer independence:** Kafka's consumer group model allows order-service, reporting-service, and artificial-intelligence-service to each consume `stock-unit.state-changed` independently, at their own pace, without affecting each other.
- **Exactly-once semantics:** Kafka's transactional producer + idempotent consumer pattern supports exactly-once processing for critical stock operations.
- **Horizontal scaling:** Adding partitions scales throughput. At 12 partitions for high-volume topics, we can run 12 consumer instances per consumer group.
- **Event replay for debugging:** Kafka's topic retention allows replaying events to reproduce issues, build new read models, or migrate data.
- **Edge-to-cloud sync:** Kafka MirrorMaker 2 provides built-in event replication from edge Redpanda to cloud Kafka, enabling the hybrid deployment model.

### Negative
- **Operational complexity:** Kafka requires ZooKeeper (or KRaft mode in Kafka 3.x+) and proper tuning of partition count, replication factor, retention, and consumer group management. Adds infrastructure expertise requirements.
- **Message size and format overhead:** JSON events are larger than binary formats (Avro, Protobuf). At 50K events/hour, this is manageable (< 50 MB/hour for typical WMS events). At much higher scale, Avro migration may be warranted.
- **Debugging difficulty:** Asynchronous event-driven flows are harder to debug than synchronous call chains. A picking saga involves 4-5 events across 3 services. Requires correlation IDs and distributed tracing to follow end-to-end flow.
- **Schema evolution discipline:** Without a schema registry (rejected for simplicity), schema evolution relies on team discipline (additive-only changes, version field). Breaking changes require coordinated topic version migration.
- **Kafka at edge:** Even Redpanda at 256 MB is a significant portion of the edge RAM budget. Alternative: reduce to embedded Kafka (KRaft single-node) at ~128 MB for very constrained environments.

### Neutral
- The 16 CDI event types in myWMS map to approximately 14 Kafka topics in Karyo. The conceptual model is preserved — events carry entity state changes — but the delivery mechanism changes from synchronous in-process to asynchronous cross-process.
- Kafka Connect (Debezium) for the outbox pattern adds a component but eliminates the risk of dual-write inconsistency.

## Alternatives Considered

### Alternative 1: RabbitMQ
- **Pros**: Simpler to operate (no ZooKeeper), lower resource requirements for simple use cases, better support for complex routing (exchanges, bindings), AMQP protocol support.
- **Cons**: No message replay (messages deleted after consumption), no log-based ordering guarantees, less suited for event sourcing patterns, smaller message throughput at scale, no built-in edge-to-cloud replication equivalent to MirrorMaker 2.
- **Why rejected**: The inability to replay messages is the primary disqualifier. Reporting-service and artificial-intelligence-service need to catch up after outages by replaying missed events. Additionally, Kafka's per-partition ordering with tenant-based partitioning provides the ordering guarantees needed for stock operation consistency.

### Alternative 2: NATS / NATS JetStream
- **Pros**: Extremely lightweight (< 20 MB binary), fast pub/sub, JetStream adds persistence and replay, simpler operations than Kafka.
- **Cons**: Smaller ecosystem (fewer client libraries, less tooling), less mature persistence layer (JetStream is newer), fewer production references in enterprise logistics, no equivalent to Kafka Connect/Debezium for outbox pattern, no Quarkus SmallRye Reactive Messaging support (requires custom integration).
- **Why rejected**: While NATS's lightweight footprint is attractive for edge deployment, the ecosystem maturity and Quarkus integration are insufficient. The lack of a Quarkus SmallRye Reactive Messaging extension would require custom producer/consumer implementations for all 10 services. Kafka/Redpanda's ecosystem (Debezium, MirrorMaker 2, monitoring tooling) provides significantly more operational tooling.

### Alternative 3: Apache Pulsar
- **Pros**: Built-in multi-tenancy, tiered storage (hot/warm/cold), combined pub/sub + message queue semantics, geo-replication, BookKeeper for durable storage.
- **Cons**: More complex architecture than Kafka (broker + BookKeeper + ZooKeeper), higher resource requirements, smaller community, fewer client libraries, no lightweight alternative for edge deployment (no Redpanda equivalent), less mature Quarkus integration.
- **Why rejected**: Pulsar's multi-tenancy and geo-replication features are attractive, but the operational complexity is higher than Kafka for our scale. The edge deployment constraint eliminates Pulsar (no lightweight alternative). Kafka with tenant-based partitioning achieves sufficient multi-tenant isolation for WMS workloads.

## Implementation Notes
- Use **Quarkus SmallRye Reactive Messaging** with the Kafka connector for producer/consumer implementation.
- Use **Kafka KRaft mode** (no ZooKeeper) for cloud deployments with Kafka 3.x+.
- Use **Redpanda** (single-node, < 256 MB) for edge deployments. No code changes required — Kafka-compatible API.
- Outbox pattern: Start with a **polling relay** (simpler to implement) in Phase 0. Evaluate Debezium CDC in Phase 1 for lower-latency event publishing.
- Configure consumer idempotency using `eventId` as deduplication key (stored in a per-consumer `processed_events` table with TTL-based cleanup).
- Monitoring: Kafka exporter for Prometheus (`kafka_consumer_group_lag`, `kafka_topic_partition_offset`).

## Related Decisions
- [ADR-001: Microservices Architecture Style](ADR-001-microservices-architecture.md)
- [ADR-004: Database-per-Service Pattern](ADR-004-database-per-service.md)
- [ADR-008: REST for Synchronous, Events for Asynchronous](../ADR-008-rest-sync-events-async.md)
- [ADR-010: Event-Driven Architecture with Choreography-Based Sagas](../ADR-010-choreography-sagas.md)
- [ADR-011: Event Sourcing Decision](../ADR-011-no-event-sourcing.md)

## References
- Apache Kafka: https://kafka.apache.org/
- Quarkus SmallRye Reactive Messaging (Kafka): https://quarkus.io/guides/kafka
- Redpanda: https://redpanda.com/
- Debezium Outbox Pattern: https://debezium.io/documentation/reference/transformations/outbox-event-router.html
- Kafka MirrorMaker 2: https://kafka.apache.org/documentation/#georeplication

## Revision History
- 2026-02-15: Initial version
