# ADR-033: Cross-Docking via Event-Driven Interceptor Pattern

> **MECHANISM TRANSLATED post-pivot** — see [ADR-037: Modular Monolith](ADR-037-modular-monolith.md).
> **Current disposition (2026-09-08): implemented commercial capability.** The original design
> body below is history, not the released configuration/API contract. Cross-docking uses opt-in
> `karyo.crossdock.*` runtime properties, not the strategy keys illustrated below. Public API
> declarations remain free; running the engine requires its installation and entitlement.
> See the [current extension contract](../extensibility-architecture.md).
>
> The `CrossDockingMatcher` SPI and the interceptor seam stand. The *mechanism* is translated: the
> interceptor is a synchronous CDI observer inside the receiving transaction, not a Kafka consumer,
> and cross-dock events fire as CDI events with the topic name reserved as an outbox `eventType`.
>
> The body below records the original decision context. The post-pivot mechanism above is the
> post-pivot direction, qualified by the current-disposition note above.


## Status
Accepted

## Context
Karyo WMS needs cross-docking capabilities — the ability to route inbound goods directly to outbound orders without intermediate putaway to storage. This is a new capability not present in myWMS (which has no cross-docking support in its source code).

Cross-docking is an edge-case optimization that depends on external factors (supplier reliability, demand timing, staging area capacity). Different warehouse clients may need radically different matching logic — some match on pre-assigned ASN data from reliable suppliers, others do opportunistic matching at receipt time against outstanding outbound demand.

**Two cross-docking types must be supported:**
1. **Pre-distributed:** Supplier pre-assigns goods to outbound orders via ASN. The `AdviceLine.crossDockDeliveryOrderId` field links inbound to outbound at advice creation time.
2. **Opportunistic:** System matches inbound goods to outbound demand at receipt time based on item, quantity, and priority. Matching logic varies significantly by client.

**Key constraints:**
- Cross-docking should work without requiring yard-management-service (optional enrichment only)
- The matching logic must be client-customizable via SPI without modifying core order-service code
- Items that don't match should fall through to normal putaway seamlessly
- Staging area expiry behavior (auto-putaway vs. alert) must be configurable per OrderStrategy

## Decision
We will implement cross-docking using an **event-driven interceptor pattern** in the existing choreography saga architecture. A CDI observer with `@Priority(10)` on the `GoodsReceiptLineReceivedEvent` fires before the default putaway observer, intercepts inbound goods, and routes matched items to staging areas instead of storage locations.

### Architecture

```
order-service                task-service           layout-service        inventory-service
     │                            │                       │                      │
     ├── goods-receipt.line       │                       │                      │
     │   -received ──────────────►│                       │                      │
     │                            │                       │                      │
     │   CrossDockInterceptor     │                       │                      │
     │   (@Priority 10)           │                       │                      │
     │   fires before putaway     │                       │                      │
     │                            │                       │                      │
     │   CrossDockingMatcher SPI  │                       │                      │
     │   checks for match         │                       │                      │
     │                            │                       │                      │
     ├── IF match found:          │                       │                      │
     │   Create CrossDockOrder    │                       │                      │
     │   Publish: cross-dock      │                       │                      │
     │   .matched ───────────────►│                       │                      │
     │                            ├── GET /cross-dock     │                      │
     │                            │   -orders (check) ───►│                      │
     │                            │◄── CrossDockOrder ────┤                      │
     │                            │                       │                      │
     │                            ├── POST /reserve ─────►│                      │
     │                            │   (staging location)  │                      │
     │                            │◄── reserved ──────────┤                      │
     │                            │                       │                      │
     │                            ├── Create transport    │                      │
     │                            │   (type: CROSS_DOCK)  │                      │
     │                            │                       │                      │
     │                            │ [Operator moves to    │                      │
     │                            │  staging area]        │                      │
     │                            │                       │                      │
     │                            ├── Confirm transport   │                      │
     │                            ├── Publish: cross-dock │                      │
     │                            │   .staged ───────────►│                      │
     │◄── cross-dock.staged ──────┤                       │                      │
     │   Update CrossDockOrder    │                       │                      │
     │   (state: STAGED)          │                       │                      │
     │   Auto-create Packet       │                       │                      │
     │   for packing/shipping     │                       │                      │
     │                            │                       │                      │
     ├── IF no match:             │                       │                      │
     │   Event falls through to   │                       │                      │
     │   default putaway observer │                       │                      │
     │   (normal flow unchanged)  │                       │                      │
```

### SPI Extensibility

Two SPI interfaces in `karyo-order-api` enable client customization without modifying core code:

```kotlin
// Client extension JARs implement this to customize matching logic
interface CrossDockingMatcher {
    fun findMatch(context: CrossDockMatchContext): CrossDockMatchResult?
    fun priority(): Int = 0  // Higher priority matchers run first
}

// Client extension JARs implement this to customize expiry behavior
interface CrossDockExpiryHandler {
    fun handleExpiry(crossDockOrder: CrossDockOrder, strategy: OrderStrategy)
}
```

The default `CrossDockingMatcher` implementation:
1. Checks pre-distributed match first (`AdviceLine.crossDockDeliveryOrderId`)
2. Falls back to opportunistic matching (query outbound DeliveryOrderLines needing the same item)
3. Scores matches by priority, urgency (due date), and quantity fit

### Data Model

A new `CrossDockOrder` entity in order-service with state machine: `CREATED → MATCHED → STAGED → PACKING → SHIPPED → COMPLETED` (with EXPIRED and CANCELLED terminal states). Three JSONB extension properties on `OrderStrategy` control behavior: `crossDockEnabled`, `crossDockExpiryAction`, `crossDockStagingMinutes`.

### Kafka Topics (4 new)

| Topic | Publisher | Consumers |
|-------|-----------|-----------|
| `karyo.orders.cross-dock.matched` | order-service | task-service, yard-management (optional) |
| `karyo.orders.cross-dock.expired` | order-service | task-service |
| `karyo.orders.cross-dock.completed` | order-service | reporting-service |
| `karyo.tasks.cross-dock.staged` | task-service | order-service |

### Putaway Guard

Task-service adds a guard to its existing putaway consumer: before creating a PUTAWAY TransportOrder, it checks (sync REST) whether a CrossDockOrder exists for the receipt line. If one exists, putaway is skipped. This is race-safe and idempotent — both observers can fire, but only one transport order is created.

## Consequences

### Positive
- **Zero impact on existing flows:** The interceptor pattern adds a priority-based observer before putaway. If no cross-dock match exists, the event falls through to the existing putaway observer unchanged.
- **Highly customizable:** The SPI pattern allows each client to provide their own matching logic (JAR compiled against `karyo-order-api` only) without touching core order-service code.
- **No new infrastructure:** Uses existing Kafka topics, CDI event bus, and choreography saga patterns. No orchestrator needed.
- **Consistent with architecture:** Follows the same choreography-based saga pattern used for picking and putaway (see ADR-010).
- **Optional yard integration:** Works without yard-management-service. If deployed, yard-management enriches dock scheduling by consuming `cross-dock.matched` events.
- **Configurable per strategy:** OrderStrategy's JSONB extensionProperties control cross-dock behavior per client without schema changes.

### Negative
- **Increased complexity in receiving flow:** The receiving flow now has a decision branch (cross-dock vs. putaway). Engineers must understand both paths when debugging receiving issues.
- **Race condition surface area:** The putaway guard relies on task-service checking order-service (sync REST) before creating transport orders. If order-service is temporarily unavailable, task-service must retry or fall back to putaway.
- **Staging area capacity management:** Cross-docked items occupy staging locations with TTL. If outbound orders are delayed, staging areas can fill up. The expiry handler mitigates this but adds operational complexity.
- **Testing burden:** Cross-docking scenarios require multi-service integration tests (order-service + task-service + inventory-service + layout-service). These are slow and complex.

### Neutral
- Cross-docking is implemented within order-service's bounded context. The `CrossDockOrder` entity is managed by order-service, keeping domain ownership clear.
- The 4 new Kafka topics follow existing naming conventions (`karyo.{service}.{entity}.{event-type}`).

## Alternatives Considered

### Alternative A: Strategy Flag on Receiving (Synchronous)
- **Approach:** Add a `crossDockEnabled` flag to `OrderStrategy`. During goods receipt, order-service synchronously queries outbound demand, creates cross-dock orders inline, and publishes a single event containing the routing decision.
- **Pros:** Simpler flow — a single service makes the entire cross-dock decision. No race conditions between observers. Easier to debug because the decision happens in one place.
- **Cons:** Increases goods receipt latency (synchronous matching query during receiving). Tight coupling between receiving and outbound demand in order-service. No CDI priority-based interception — harder to extend.
- **Why rejected:** The synchronous approach adds latency to every goods receipt line even when cross-docking is disabled. The event-driven approach only fires the interceptor when a CDI observer is registered, and the matching query runs asynchronously after the receipt is committed.

### Alternative B: Separate Matching Job (Async Batch)
- **Approach:** A scheduled job (every N minutes) queries recent unmatched goods receipt lines against outstanding outbound demand. Creates cross-dock orders in bulk for any matches found.
- **Pros:** Zero impact on receiving latency. Batch matching can be more efficient for high-volume warehouses. Simple retry logic (rerun the job).
- **Cons:** Introduces latency between receipt and cross-dock decision (up to N minutes). Items may already be putaway by the time the match is found, requiring a second transport to staging. Not suitable for time-sensitive cross-docking. Miss pre-distributed matches entirely (supplier assigned via ASN).
- **Why rejected:** The batch delay is unacceptable for pre-distributed cross-docking where the supplier has already assigned items to outbound orders. For opportunistic matching, the delay means items might already be putaway, wasting labor. Real-time interception avoids both problems.

## Implementation Notes
- **Current runtime:** Cross-docking is implemented as a synchronous receiving interceptor with ordinary putaway as the structural fallback.
- **Putaway guard must be idempotent:** repeated receipt processing must not create duplicate cross-dock or putaway work.
- **Staging area monitoring:** Add Grafana dashboard panel for staging area utilization (occupied vs. capacity), cross-dock orders by state, and average staging duration.

## Related Decisions
- [ADR-001: Microservices Architecture Style](superseded/ADR-001-microservices-architecture.md)
- [ADR-008: REST for Synchronous, Events for Asynchronous](ADR-008-rest-sync-events-async.md)
- [ADR-010: Choreography-Based Sagas](ADR-010-choreography-sagas.md) — Cross-docking follows the same choreography pattern

## References
- Karyo extensibility architecture: [docs/architecture/extensibility-architecture.md](../extensibility-architecture.md)
- Modular monolith runtime: [ADR-037](ADR-037-modular-monolith.md)

## Revision History
- 2026-02-18: Initial version
