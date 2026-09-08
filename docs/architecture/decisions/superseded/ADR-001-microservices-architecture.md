# ADR-001: Microservices Architecture Style

> **SUPERSEDED.** Superseded by [ADR-037: Modular Monolith](../ADR-037-modular-monolith.md) (2026-06).
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
Karyo WMS is a greenfield rebuild of the myWMS open-source warehouse management system. myWMS is a monolithic Java EE application deployed as a single EAR file containing 9 Maven modules, 861 Java files, and 37+ JPA entities. While myWMS has proven domain logic (13-pass stock selection algorithm, multi-phase location finder, comprehensive state machines), the monolithic architecture creates significant limitations:

- **Scaling:** The entire application must scale together, even though workloads vary dramatically — inventory queries spike during wave planning, receiving surges during truck arrivals, and reporting is CPU-intensive but latency-tolerant.
- **Deployment:** Any change requires redeploying the entire EAR, increasing risk and downtime for a 24/7 warehouse operation.
- **Edge deployment:** The full monolith exceeds the < 2GB RAM constraint for edge warehouse deployments.
- **Team independence:** A single codebase creates coupling between development teams working on different functional areas.
- **Technology evolution:** The monolith locks all components to the same Java EE stack, preventing adoption of purpose-built technologies (e.g., pgvector for AI, Redpanda for lightweight Kafka at the edge).
- **Multi-tenancy isolation:** myWMS uses a shared Client entity but lacks true tenant isolation at the data and runtime level.

The myWMS source analysis revealed clear bounded contexts: Inventory, Product, Layout, Inbound (Receiving), Outbound (Pick-Pack-Ship), Tasks, and Strategies. These contexts communicate through CDI events within the monolith — a pattern that maps naturally to asynchronous inter-service events.

## Decision
We will adopt a **microservices architecture** with **10 independently deployable services**, each aligned to a DDD bounded context extracted from the myWMS domain analysis:

1. **auth-service** — Identity, authentication, tenant management
2. **inventory-service** — Stock tracking, unit loads, audit journal
3. **product-service** — Product master data, units of measure
4. **warehouse-layout-service** — Locations, areas, zones, putaway strategies
5. **order-service** — Inbound (receiving) and outbound (pick-pack-ship)
6. **task-service** — Transport, replenishment, stocktaking
7. **integration-hub** — ERP, carrier, e-commerce adapters
8. **reporting-service** — Analytics, dashboards, CQRS read models
9. **artificial-intelligence-service** — NL queries, predictions, RAG
10. **yard-management-service** — Dock scheduling, trailer tracking

Additionally, a stateless **mobile-api-gateway** (BFF) aggregates APIs for mobile workflows.

Each service:
- Owns its bounded context, aggregate roots, and business rules
- Has a private PostgreSQL database/schema (no cross-service foreign keys)
- Communicates asynchronously via Kafka events for state changes
- Uses synchronous REST for critical-path queries (stock selection, location finding)
- Is independently deployable, scalable, and testable
- Follows Twelve-Factor App principles

### Service Boundary Rationale

The boundaries were determined by analyzing myWMS's internal coupling patterns:

| Boundary | myWMS Evidence | Coupling Pattern |
|----------|---------------|-----------------|
| Inventory separate from Orders | `InventoryBusiness` has no direct imports of `DeliveryOrder`/`PickingOrder`; communication is through `StockUnit` state changes and CDI events | Loose coupling via events |
| Layout separate from Inventory | `LocationFinderBean` has no direct stock queries; it evaluates location capacity independent of what stock exists | Independent aggregate |
| Tasks separate from Orders | `TransportBusiness` manages task lifecycle independently; linked to orders only via UnitLoad ID references | ID-based reference |
| Product separate from Inventory | `ItemData` is a reference entity queried by all services but mutated only by master data management | Read-heavy reference data |
| Stocktaking in Tasks (not Inventory) | `LOSStockTakingProcessCompBean` manages the counting workflow; only the final adjustment calls `InventoryBusiness` | Workflow vs. data ownership |

## Consequences

### Positive
- **Independent scaling:** Inventory service scales during wave planning; order service scales during peak shipping; reporting service can use larger instances without affecting operational services.
- **Edge deployment possible:** Core services (auth, inventory, product, layout, orders, tasks) fit within < 2GB RAM when each service runs at 128-256 MB. Cloud-only services (AI, reporting, integration) stay in the cloud.
- **Independent deployment:** Changes to picking logic deploy only the order-service, reducing blast radius for the 24/7 warehouse operation.
- **Technology freedom:** AI service can adopt pgvector and LangChain4j without affecting inventory service. Edge deployments can use Redpanda instead of Kafka.
- **Team autonomy:** Teams own bounded contexts end-to-end (API, business logic, database, events).
- **Fault isolation:** A reporting service failure does not affect picking operations.
- **Clear domain model:** Each service owns its aggregate roots, making invariant enforcement explicit and testable.

### Negative
- **Operational complexity:** 10+ services to deploy, monitor, and debug. Requires Kubernetes expertise, distributed tracing (OpenTelemetry), and centralized logging.
- **Eventual consistency:** Cross-service operations (e.g., picking saga: order-service reserves stock in inventory-service) require saga patterns and compensating transactions.
- **Network latency:** Synchronous cross-service calls (stock selection during pick line generation) add network hops. Mitigated by co-locating services in the same K8s cluster and using connection pooling.
- **Data duplication:** Reference data (products, locations) is cached locally in consuming services, requiring cache invalidation strategies.
- **Distributed debugging:** Tracing a picking workflow requires correlating logs across 3-4 services. Requires correlation IDs and distributed tracing infrastructure.
- **Integration testing complexity:** End-to-end tests require spinning up multiple services with Testcontainers.
- **Cross-service queries:** No JOINs across service databases. Reporting service must build materialized views from Kafka events.

### Neutral
- The myWMS CDI event pattern (16 distinct event types) maps naturally to Kafka topics, reducing the conceptual gap in migration.
- Each service requires its own Flyway migration scripts, CI/CD pipeline, and health checks — more infrastructure per service, but more isolation.
- The mobile-api-gateway (BFF) pattern adds a service, but simplifies mobile client development by aggregating multi-service calls into single round-trips.

## Alternatives Considered

### Alternative 1: Modular Monolith
- **Pros**: Simpler deployment (single artifact), in-process communication (no network latency), ACID transactions across modules, easier debugging, lower operational overhead.
- **Cons**: Cannot independently scale modules; cannot deploy to edge with < 2GB constraint (full monolith too large); all modules share the same runtime and technology stack; deployment risk for 24/7 operations (redeploy everything for any change).
- **Why rejected**: The edge deployment constraint (< 2GB RAM for core warehouse operations) is a hard requirement. A modular monolith cannot selectively deploy only the modules needed at the edge. Additionally, the 24/7 uptime requirement demands independent deployability to minimize blast radius.

### Alternative 2: Service-Oriented Architecture (SOA)
- **Pros**: Service reuse, enterprise integration patterns, proven in large organizations.
- **Cons**: Heavier infrastructure (ESB), typically uses SOAP/WSDL (poor fit for mobile clients), centralized governance slows evolution, shared data models create coupling.
- **Why rejected**: SOA's centralized ESB approach contradicts the lightweight, edge-deployable requirement. The shared canonical data model would reintroduce the coupling we're trying to eliminate. Modern microservices with lightweight Kafka events achieve the same decoupling without the ESB overhead.

### Alternative 3: Serverless (AWS Lambda / Cloud Functions)
- **Pros**: Zero infrastructure management, pay-per-use scaling, no idle cost.
- **Cons**: Cold start latency (unacceptable for real-time picking operations), vendor lock-in (contradicts on-premise requirement), no edge deployment option, connection pooling challenges with PostgreSQL, complex state management for multi-step warehouse workflows.
- **Why rejected**: Warehouse operations require consistent sub-200ms response times; cold starts (1-5 seconds for JVM-based functions) are unacceptable. The on-premise/edge deployment requirement eliminates cloud-only serverless platforms. The long-running, stateful nature of warehouse workflows (picking sagas, transport chains) is a poor fit for the request-response serverless model.

## Implementation Notes
- Start with the inventory-service as the first proof-of-concept (Phase 0) — it has the richest domain logic (PickingStockFinder, InventoryBusiness) and is called by multiple other services.
- Use Gradle multi-module build initially, with the option to split into separate repositories per service as teams grow.
- Shared libraries (event schemas, domain value objects, error types) published as Gradle artifacts, NOT shared source code.
- Inter-service contracts defined via OpenAPI specs and Kafka event schemas, tested with Pact contract tests.
- Service discovery via Kubernetes DNS (no Eureka/Consul needed).

## Related Decisions
- [ADR-002: Quarkus as Microservices Framework](../ADR-002-quarkus-framework.md)
- [ADR-004: Database-per-Service Pattern](ADR-004-database-per-service.md)
- [ADR-006: Apache Kafka for Event Bus](ADR-006-kafka-event-bus.md)
- [ADR-007: Kubernetes as Orchestration Platform](ADR-007-kubernetes-orchestration.md)
- [ADR-008: REST for Synchronous, Events for Asynchronous](../ADR-008-rest-sync-events-async.md)
- [ADR-010: Event-Driven Architecture with Choreography-Based Sagas](../ADR-010-choreography-sagas.md)

## References
- Newman, Sam. "Building Microservices" (2nd Edition) — O'Reilly
- Vernon, Vaughn. "Implementing Domain-Driven Design" — bounded context extraction
- Twelve-Factor App: https://12factor.net/
- Current public workflow contracts: [Stock Selection](../../../functional/stock-selection.md), [Order Picking](../../../functional/picking.md), and [Putaway Location Finder](../../../functional/location-finder.md)

## Revision History
- 2026-02-15: Initial version
