# ADR-008: REST for Synchronous, Events for Asynchronous

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md).
>
> REST remains the **external** API surface. The internal half is superseded: state-change
> notification is CDI events, not Kafka. The outbox has an active webhook relay and copilot
> reader. Reserved topic names and `DomainEvent<T>` are not the universal CDI/webhook envelope;
> see the [webhook catalog](../../integration/webhook-event-catalog.md) for actual wire contracts.


## Status
Partially superseded

## Context
Karyo WMS microservices need to communicate with each other in two fundamentally different patterns:

1. **Synchronous (request-response):** The caller needs an immediate answer before proceeding. Example: order-service must know the available stock before creating a pick line. If inventory-service is down, the operation cannot proceed.

2. **Asynchronous (event notification):** The caller publishes a state change and does not wait for a response. Example: inventory-service publishes `stock-unit.state-changed` for reporting-service to build analytics. If reporting-service is temporarily down, the event waits in Kafka.

The myWMS monolith uses both patterns internally:
- **Synchronous:** Direct method calls between EJB beans (e.g., `PickingOrderLineGenerator` calls `PickingStockFinder.findFirstSourceStock()`)
- **Asynchronous:** CDI events (e.g., `StockUnitStateChangeEvent` observed by journal handler and extension hooks)

The challenge is mapping these patterns to inter-service communication while maintaining the performance requirements (sub-200ms API response time for critical operations) and the consistency guarantees that the monolith provides.

## Decision
We will use a **dual communication strategy:**

- **REST (JAX-RS via RESTEasy Reactive)** for all synchronous request-response communication
- **Kafka events** for all asynchronous state change propagation

### Synchronous Communication (REST)

Used when the caller **cannot proceed without the response**.

| Caller | Callee | Endpoint | Pattern | myWMS Equivalent |
|--------|--------|----------|---------|-----------------|
| order-service | inventory-service | `GET /api/internal/stock-selection` | Query + Response | `PickingStockFinder.findFirstSourceStock()` |
| order-service | inventory-service | `POST /api/internal/stock-units/{id}/reserve` | Command + Response | `StockUnit.setReservedAmount()` |
| order-service | inventory-service | `POST /api/internal/stock-units/{id}/transfer` | Command + Response | `InventoryBusiness.transferStock()` |
| order-service | product-service | `GET /api/v1/products/by-number/{number}` | Query + Response | `ItemDataService.getByItemNumber()` |
| task-service | warehouse-layout-service | `GET /api/internal/location-finder` | Query + Response | `LocationFinderBean.findStorageLocation()` |
| task-service | warehouse-layout-service | `POST /api/internal/locations/{id}/reserve` | Command + Response | `LocationReservation` creation |
| task-service | inventory-service | `POST /api/internal/unit-loads/{id}/transfer` | Command + Response | `InventoryBusiness.transferUnitLoad()` |
| task-service | inventory-service | `POST /api/internal/stock-units/{id}/adjust` | Command + Response | `InventoryBusiness.changeAmount()` |

**Internal API conventions:**
- Prefix: `/api/internal/` (not exposed through API Gateway)
- Authentication: service-to-service JWT tokens (mTLS in production)
- Timeouts: 2 seconds per call (see [ADR-002](ADR-002-quarkus-framework.md) resilience patterns)
- Circuit breakers: 50% failure ratio, 5s delay
- Retry: 3 attempts with 200ms delay (idempotent operations only)

### Asynchronous Communication (Kafka Events)

Used for **state change notifications** where the producer does not need an immediate response.

| Producer | Event | Key Consumers | myWMS Equivalent |
|----------|-------|---------------|-----------------|
| inventory-service | `stock-unit.state-changed` | order-service, reporting-service | `StockUnitStateChangeEvent` (CDI) |
| inventory-service | `stock-unit.amount-changed` | reporting-service, artificial-intelligence-service | `StockUnitAmountChangeEvent` (CDI) |
| inventory-service | `unit-load.transferred` | task-service, reporting-service | `UnitLoadTransferEvent` (CDI) |
| order-service | `delivery-order.state-changed` | integration-hub, reporting-service | `DeliveryOrderStateChangeEvent` (CDI) |
| order-service | `picking-order.state-changed` | task-service | `PickingOrderStateChangeEvent` (CDI) |
| order-service | `goods-receipt.state-changed` | task-service, integration-hub | `GoodsReceiptStateChangeEvent` (CDI) |
| task-service | `transport-order.completed` | inventory-service, layout-service | `TransportOrderStateChangeEvent` (CDI) |
| layout-service | `fix-assignment.threshold-breached` | task-service | (scheduled check in myWMS) |

### Decision Matrix: When to Use REST vs. Kafka

| Criteria | Use REST | Use Kafka |
|----------|----------|-----------|
| Caller needs response to continue? | Yes | No |
| Failure should block the operation? | Yes | No |
| Consistency requirement | Strong (immediate) | Eventual (seconds-minutes) |
| Operation is idempotent? | Preferred | Required |
| Multiple consumers for same data? | No (use Kafka) | Yes |
| Analytics/reporting feed? | No (use Kafka) | Yes |
| Critical transaction path? | Yes | No |

## Consequences

### Positive
- **Clarity of intent:** REST calls clearly signal "I need this now and cannot proceed without it." Kafka events clearly signal "this happened, react if you care." Developers know which pattern to apply for new inter-service communication.
- **Preserved consistency:** The critical-path operations (stock selection during picking, location finding during putaway) use synchronous REST, maintaining the same consistency guarantees as myWMS's direct EJB method calls.
- **Failure isolation for non-critical paths:** Reporting-service or artificial-intelligence-service outages do not block warehouse operations. Events accumulate in Kafka and are processed when services recover.
- **OpenAPI for contracts:** REST endpoints generate OpenAPI specs, providing contract testing and client SDK generation. Kafka event schemas use JSON Schema for validation.
- **Debugging REST paths:** Synchronous calls produce request/response logs with correlation IDs, making critical-path debugging straightforward.
- **Quarkus ecosystem alignment:** Quarkus RESTEasy Reactive (REST) and SmallRye Reactive Messaging (Kafka) are both first-class extensions with excellent documentation and testing support.

### Negative
- **REST availability dependency:** Synchronous REST calls create runtime coupling — if inventory-service is down, order-service cannot generate pick lines. Mitigated by circuit breakers and fallback strategies, but the fundamental dependency exists.
- **Two communication mechanisms to maintain:** Developers must understand and correctly apply both REST and Kafka patterns. Test infrastructure must cover both (REST-assured for REST, Testcontainers Kafka for events).
- **No GraphQL aggregation:** Clients that need data from multiple services must make multiple REST calls or use the mobile-api-gateway (BFF) for aggregation. GraphQL would provide more flexible aggregation but is not adopted (see below).

### Neutral
- The dual strategy mirrors exactly what myWMS does internally (direct method calls for critical path, CDI events for notifications). This reduces the conceptual gap for developers migrating domain logic.
- Internal REST endpoints (`/api/internal/`) are separated from external endpoints (`/api/v1/`) and are not exposed through the API Gateway.

## Alternatives Considered

### Alternative 1: GraphQL (instead of REST for external APIs)
- **Pros**: Flexible client-driven queries, reduces over-fetching and under-fetching, single endpoint for aggregated data, strong typing via schema.
- **Cons**: Quarkus GraphQL support is less mature than REST, N+1 query problem requires DataLoader patterns, caching is more complex (no HTTP-level caching), security (field-level authorization) is harder, mobile BFF pattern already solves the aggregation need, WMS domain has well-defined views (not ad-hoc queries).
- **Why rejected**: The WMS domain has well-defined, predictable views (stock list, order details, pick list) rather than ad-hoc client-driven queries. The mobile-api-gateway (BFF) already aggregates multi-service data for mobile clients. GraphQL's complexity (schema stitching, DataLoader, field-level auth) is not justified when REST endpoints with targeted DTOs serve the known use cases. May be reconsidered in Phase 3+ for the manager dashboard if ad-hoc query needs emerge.

### Alternative 2: gRPC for All Internal Communication
- **Pros**: Binary protocol (lower latency, smaller payload than JSON), strong typing via Protocol Buffers, bidirectional streaming, code generation for client/server.
- **Cons**: Harder to debug (binary protocol, not human-readable), requires additional tooling (grpcurl, Bloom RPC), less browser-friendly (requires gRPC-Web proxy for frontend), Quarkus gRPC support exists but is less commonly used than REST, adds Protobuf schema management overhead.
- **Why rejected**: For the current throughput (30-50K events/hour, < 1000 REST calls/second between services), the latency difference between JSON REST and gRPC is negligible (< 1ms per call). REST's human-readable format significantly simplifies debugging during Phase 0-1 development. gRPC may be adopted for specific high-throughput internal paths (e.g., stock selection during large wave planning) in Phase 3+ if REST latency becomes a bottleneck.

### Alternative 3: Pure Async (No Synchronous Communication)
- **Pros**: Maximum decoupling, no runtime service dependencies, services operate fully independently.
- **Cons**: Eventual consistency for ALL operations (including stock reservation — could lead to double-picks), complex choreography for operations that need immediate feedback (picking requires knowing available stock NOW), significantly harder to reason about system state, longer end-to-end latency for user-facing operations.
- **Why rejected**: Warehouse operations have hard consistency requirements. An operator scanning a product for picking needs an immediate answer about stock availability. A putaway operation needs to know the target location immediately. Pure async would require maintaining local read models of all cross-service data (CQRS everywhere), introducing massive complexity for marginal decoupling benefit on critical paths.

## Implementation Notes
- **REST clients:** Use Quarkus REST Client Reactive with `@RegisterRestClient` interface annotations. Type-safe, code-generated from OpenAPI specs.
- **Resilience:** All REST clients annotated with `@CircuitBreaker`, `@Timeout(2s)`, `@Retry(3)`, and `@Fallback` (see system architecture resilience patterns).
- **Correlation IDs:** Every REST call and Kafka event carries a `correlationId` (generated at the API Gateway for external requests, propagated through the call chain). OpenTelemetry trace context (W3C Trace Context) provides automatic propagation.
- **Contract testing:** Use Pact for REST contract tests between services. Producer tests verify the contract; consumer tests verify expectations.
- **Event schema testing:** JSON Schema validation on Kafka producers/consumers. Schema files versioned alongside service code.

## Related Decisions
- [ADR-001: Microservices Architecture Style](superseded/ADR-001-microservices-architecture.md)
- [ADR-006: Apache Kafka for Event Bus](superseded/ADR-006-kafka-event-bus.md)
- [ADR-009: API Gateway Pattern (Kong)](superseded/ADR-009-api-gateway-kong.md)
- [ADR-010: Event-Driven Architecture with Choreography-Based Sagas](ADR-010-choreography-sagas.md)

## References
- Quarkus RESTEasy Reactive: https://quarkus.io/guides/resteasy-reactive
- Quarkus REST Client Reactive: https://quarkus.io/guides/rest-client-reactive
- Quarkus SmallRye Reactive Messaging (Kafka): https://quarkus.io/guides/kafka
- Richardson, Chris. "Microservices Patterns" — Chapter 3: Interprocess communication

## Revision History
- 2026-02-15: Initial version
