# ADR-010: Event-Driven Architecture with Choreography-Based Sagas

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md).
>
> Not needed in-process — a multi-module operation is now one transaction. The choreography
> pattern re-applies to whatever is extracted if a module is ever split out.


## Status
Partially superseded

## Context
Karyo WMS decomposes myWMS into microservices with database-per-service (see [ADR-004](superseded/ADR-004-database-per-service.md)). This means operations that were ACID transactions within the monolith now span multiple services and databases. For example:

**Picking workflow (most complex):**
1. Order-service calls inventory-service to select stock (sync REST)
2. Inventory-service reserves stock (increases `reservedAmount`)
3. Order-service creates picking order lines
4. Order-service groups picks into picking orders
5. Operator executes picks
6. Order-service confirms pick → inventory-service transfers stock
7. If picking fails → inventory-service must release reserved stock (compensation)

**Putaway workflow:**
1. Order-service confirms goods receipt line → publishes event
2. Task-service creates transport order
3. Task-service calls layout-service to find storage location (sync REST)
4. Layout-service creates location reservation
5. Operator confirms transport → task-service calls inventory-service to transfer unit load
6. Layout-service releases reservation and updates allocation
7. If transport cancelled → layout-service must release reservation (compensation)

In myWMS, these are single JTA transactions. In Karyo, they must be **sagas** — sequences of local transactions with compensating actions for rollback.

The saga can be implemented with two approaches:
- **Choreography:** Each service publishes events and other services react autonomously. No central coordinator.
- **Orchestration:** A central orchestrator (Camunda, Temporal) directs each step and handles compensation.

## Decision
We will use **choreography-based sagas** for all cross-service distributed transactions in Karyo WMS. There will be no central orchestrator.

### Picking Saga (Choreography)

```
order-service                inventory-service           reporting-service
     │                             │                           │
     ├── Release delivery order    │                           │
     │   (state: RELEASED)         │                           │
     │                             │                           │
     ├──── GET /stock-selection ──►│                           │
     │◄─── stock candidates ──────┤                           │
     │                             │                           │
     ├── POST /reserve ──────────►│                           │
     │◄─── reserved ──────────────┤                           │
     │                             │                           │
     ├── Create pick lines         │                           │
     ├── Group into pick orders    │                           │
     │                             │                           │
     ├── Publish: picking-order    │                           │
     │   .state-changed(PROC)     │                           │
     │                             │                           │
     │ [Operator picks]            │                           │
     │                             │                           │
     ├── Confirm pick line         │                           │
     ├── POST /transfer ─────────►│                           │
     │◄─── transferred ───────────┤                           │
     │                             ├── Publish: stock-unit     │
     │                             │   .state-changed(PICKED) │
     │                             │            ──────────────►│
     │                             │                           │ (analytics)
     │                             │                           │
     │ [ON FAILURE]                │                           │
     ├── Cancel pick lines         │                           │
     ├── POST /unreserve ────────►│                           │
     │◄─── released ──────────────┤                           │
```

### Putaway Saga (Choreography)

```
order-service          task-service         layout-service       inventory-service
     │                      │                     │                      │
     ├── Publish: goods     │                     │                      │
     │   -receipt.line      │                     │                      │
     │   -received         │                     │                      │
     │   ──────────────────►│                     │                      │
     │                      ├── GET /location     │                      │
     │                      │   -finder ─────────►│                      │
     │                      │◄── location ────────┤                      │
     │                      │                     │                      │
     │                      ├── POST /reserve ───►│                      │
     │                      │◄── reserved ────────┤                      │
     │                      │                     │                      │
     │                      ├── Create transport  │                      │
     │                      │   order             │                      │
     │                      │                     │                      │
     │                      │ [Operator moves]    │                      │
     │                      │                     │                      │
     │                      ├── Confirm transport │                      │
     │                      ├── POST /transfer ──│──────────────────────►│
     │                      │◄── transferred ────│───────────────────────┤
     │                      │                     │                      │
     │                      ├── DELETE /reserve ─►│                      │
     │                      │◄── released ────────┤                      │
     │                      │                     ├── Update allocation  │
     │                      │                     │                      │
     │                      │ [ON FAILURE]        │                      │
     │                      ├── Cancel transport  │                      │
     │                      ├── DELETE /reserve ─►│                      │
     │                      │◄── released ────────┤                      │
```

### Compensation Rules

| Saga Step | Compensation Action | Trigger |
|-----------|-------------------|---------|
| Stock reserved in inventory-service | Release reservation (`reservedAmount -= amount`) | Pick line cancelled, pick order cancelled |
| Location reserved in layout-service | Delete reservation | Transport order cancelled, transport timeout |
| Pick line created in order-service | Cancel pick line (state: CANCELLED) | Insufficient stock discovered during pick |
| Transport order created in task-service | Cancel transport order | Location no longer available |

### Correlation and Tracing

Every saga instance is tracked via:
- **correlationId:** Generated at saga start (e.g., when delivery order is released), propagated through all REST calls and Kafka events
- **OpenTelemetry trace context:** W3C Trace Context propagated across REST and Kafka for distributed tracing
- **Saga state inference:** No explicit saga state table. Instead, the aggregate state of involved entities (delivery order state, pick order states, stock unit states) implicitly represents saga progress. The `OrderStateCalculator` computes the overall state from child states.

### Outbox Pattern for Reliable Events

Events are published within the local transaction using the outbox pattern (see [ADR-006](superseded/ADR-006-kafka-event-bus.md)):

```kotlin
@Transactional
fun confirmPickLine(pickLineId: Long, pickedAmount: BigDecimal) {
    val pickLine = pickLineRepo.findById(pickLineId)
    pickLine.state = OrderState.PICKED
    pickLine.pickedAmount = pickedAmount

    // Write event to outbox within the same transaction
    outboxRepo.persist(OutboxEvent(
        aggregateType = "picking-order-line",
        aggregateId = pickLineId.toString(),
        eventType = "picking-order-line.confirmed",
        payload = PickLineConfirmedPayload(pickLineId, pickedAmount),
    ))
    // Transaction commits both the state change and the outbox event atomically
    // Relay publishes outbox event to Kafka
}
```

## Consequences

### Positive
- **No single point of failure:** There is no central orchestrator that, if it fails, blocks all workflows. Each service manages its own part of the saga independently.
- **Loose coupling:** Services react to events without knowing the full workflow. Inventory-service does not know whether a reservation is for picking or stocktaking — it just processes reserve/unreserve commands.
- **Natural fit for WMS workflows:** myWMS workflows are linear state machines (not complex decision trees). State progresses forward (CREATED → RELEASED → STARTED → PICKED → SHIPPED) with clear compensation paths. Choreography handles this well.
- **No additional infrastructure:** No Camunda, Temporal, or other orchestration engine to deploy, manage, and monitor. Saves 256-512 MB RAM at the edge.
- **Independent evolution:** Adding a new event consumer (e.g., artificial-intelligence-service observing pick patterns) requires no changes to existing services. The new consumer subscribes to existing Kafka topics.
- **Outbox pattern ensures reliability:** Events are published atomically with business state changes, preventing dual-write inconsistency.

### Negative
- **Harder to visualize end-to-end flow:** With choreography, there is no single place that shows the complete picking saga flow. Understanding the saga requires reading event producers and consumers across multiple services. Mitigated by documentation (this ADR) and distributed tracing (Jaeger).
- **Compensation complexity:** Each service must implement its own compensation logic. If a new failure mode is introduced, all affected services must be updated. Mitigated by keeping compensations simple (reverse the action: unreserve, release, cancel).
- **Debugging difficulty:** When a saga fails midway, diagnosing the cause requires correlating logs and events across 3-4 services. Requires correlation IDs and distributed tracing infrastructure.
- **No saga timeout management:** Without an orchestrator, there is no built-in mechanism to detect and clean up stuck sagas. Mitigated by: (1) TTL on location reservations (auto-expire), (2) scheduled cleanup jobs for stale reservations, (3) monitoring alerts on orphaned reservations.
- **Risk of event ordering issues:** If events are processed out of order (e.g., `pick-line.confirmed` arrives before `stock-unit.reserved`), consumers must handle this gracefully. Mitigated by Kafka's per-partition ordering with tenant-based partitioning.

### Neutral
- The `OrderStateCalculator` pattern from myWMS (computing parent DeliveryOrder state from child PickingOrder/Packet states) provides implicit saga state tracking without an explicit saga state table.
- Saga compensation in WMS is simpler than in payment systems because most compensations are "undo" operations (unreserve stock, release location, cancel order) rather than complex multi-party settlements.

## Alternatives Considered

### Alternative 1: Orchestration-Based Sagas (Camunda / Temporal)
- **Pros**: Central visibility of saga state and progress, built-in timeout and compensation management, visual BPMN workflow designer (Camunda), automatic retry and dead letter handling, explicit saga state machine makes debugging easier.
- **Cons**: Additional infrastructure to deploy and manage (Camunda requires PostgreSQL + JVM, Temporal requires PostgreSQL + Go services), 256-512 MB RAM at minimum (exceeds edge budget), introduces a central coordinator that becomes a single point of failure, adds coupling (all saga participants depend on the orchestrator), overkill for linear state machines (BPMN is designed for complex branching workflows).
- **Why rejected**: The edge deployment constraint (< 2GB total RAM) cannot accommodate an additional orchestration engine. WMS workflows are predominantly linear state machines, not complex branching decision trees that benefit from BPMN modeling. The `OrderStateCalculator` already provides saga state inference from child entity states. If complex, branching workflows emerge in Phase 3+ (e.g., multi-facility order routing), an orchestrator may be reconsidered for cloud-only deployments.

### Alternative 2: Pure Synchronous Transactions
- **Pros**: Simplest to implement, immediate consistency, familiar programming model, no saga complexity.
- **Cons**: Creates tight runtime coupling (all services must be available simultaneously), does not scale for large wave releases (hundreds of orders simultaneously), network failures in the middle of a transaction require complex retry/rollback logic at the caller, no event trail for analytics/reporting.
- **Why rejected**: Pure synchronous transactions with 2PC (two-phase commit) across microservice databases are impractical. The performance overhead of distributed locking and the availability requirement (all services must be up simultaneously) contradict the fault isolation and scaling goals of the microservices architecture. Additionally, the async event stream for reporting and AI services would still be needed.

## Implementation Notes
- **Idempotent consumers:** Every Kafka consumer must handle duplicate events. Use `eventId` as deduplication key, stored in a `processed_events` table with TTL-based cleanup (30-day retention).
- **Reservation TTL:** Location reservations expire after 4 hours (configurable). A scheduled job in layout-service cleans up expired reservations.
- **Stale saga detection:** A monitoring alert fires if a delivery order remains in RELEASED state for > 2 hours without progressing to PICKED. This indicates a potentially stuck saga.
- **Correlation ID generation:** The API Gateway (Kong) generates a `correlationId` for each external request. Internal requests propagate this ID. Kafka events include it in the event envelope.
- **Testing:** Saga end-to-end tests use Testcontainers to spin up all participating services + Kafka + PostgreSQL. Test scenarios include happy path, compensation on failure, and out-of-order event handling.

## Related Decisions
- [ADR-001: Microservices Architecture Style](superseded/ADR-001-microservices-architecture.md)
- [ADR-004: Database-per-Service Pattern](superseded/ADR-004-database-per-service.md)
- [ADR-006: Apache Kafka for Event Bus](superseded/ADR-006-kafka-event-bus.md)
- [ADR-008: REST for Synchronous, Events for Asynchronous](ADR-008-rest-sync-events-async.md)
- [ADR-011: Event Sourcing Decision](ADR-011-no-event-sourcing.md)

## References
- Richardson, Chris. "Microservices Patterns" — Chapter 4: Managing transactions with sagas
- Choreography-based saga pattern: https://microservices.io/patterns/data/saga.html
- Debezium Outbox Pattern: https://debezium.io/documentation/reference/transformations/outbox-event-router.html
- Current outbound workflow: [Order Picking](../../functional/picking.md)
- Current putaway workflow: [Putaway Location Finder](../../functional/location-finder.md)

## Revision History
- 2026-02-15: Initial version
