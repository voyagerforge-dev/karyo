# ADR-037: Modular Monolith (supersedes the microservices architecture)

> **Implementation context (2026-09-08):** the runtime decision stands; the original body
> records its earlier implementation state. The outbox now has an active webhook relay and
> copilot recent-activity reader, not a dormant/no-consumer state. Observer transaction phases
> and scheduler boundaries vary; auto-putaway runs after receiving commits. Performance figures
> below are targets, not a benchmark certificate. [API standards](../api-standards.md),
> [extensions](../extensibility-architecture.md) and [observability](../observability-architecture.md)
> describe the current contracts. The unexplained monitor dependency remains unresolved.

## Status
Accepted (2026-06-11; recorded retroactively 2026-07-19)

Supersedes [ADR-001](superseded/ADR-001-microservices-architecture.md),
[ADR-004](superseded/ADR-004-database-per-service.md),
[ADR-006](superseded/ADR-006-kafka-event-bus.md),
[ADR-009](superseded/ADR-009-api-gateway-kong.md),
[ADR-021](superseded/ADR-021-mtls-for-service-communication.md),
[ADR-031](superseded/ADR-031-strangler-fig-migration.md),
[ADR-032](superseded/ADR-032-dual-write-data-migration.md).
Partially supersedes [ADR-008](ADR-008-rest-sync-events-async.md),
[ADR-010](ADR-010-choreography-sagas.md), [ADR-020](ADR-020-multi-tenancy-strategy.md).

> **Why this ADR is dated later than the decision.** The pivot happened in June 2026 and was
> implemented, merged and deployed, but was never written up as a decision — it survived only as
> internal notes and as annotations inside the ADRs it invalidated. That gap was found on
> 2026-07-19 while archiving pre-pivot documentation: seven ADRs describing a system that no
> longer exists were still marked `Accepted`, and nothing recorded what replaced them. This ADR
> closes that gap.

## Context

The original architecture (ADR-001) specified 9 independently deployable microservices, each
owning its own PostgreSQL database (ADR-004), communicating over REST for the critical path and
Kafka for notifications (ADR-006, ADR-008), fronted by a Kong API gateway (ADR-009), secured
between services with mTLS (ADR-021), coordinated by choreography sagas (ADR-010), and deployed
to Kubernetes (ADR-007) via ArgoCD (ADR-022).

By June 2026 the PoC and early milestones had produced enough evidence to judge that design
against reality:

- **The deployment target is one bounded host.** Karyo runs as a silo - one instance per company -
  on a single customer-controlled container host or cloud VM. Nine services, nine databases, a
  Kafka cluster and a service mesh impose fixed operational overhead on every deployment for the
  life of the product.
- **Tenancy is silo, not SaaS.** With one instance per customer, the horizontal-scaling argument
  for microservices largely evaporates: the scaling unit is the customer, not the service.
- **No independent deployment pressure existed.** All modules are built by one team, released
  together, and versioned together. The primary benefit microservices buy — independent
  deploy cadence — was not being consumed.
- **The distribution tax was real and immediate.** Cross-service calls needed REST clients,
  retries, circuit breakers, contract tests, and saga compensation for operations that are a
  single database transaction in one process.
- **The domain is highly transactional.** Reserving stock, picking, and transferring touch
  several modules in one logical operation. Distributed, these need sagas and eventual
  consistency; co-located, they need a `@Transactional` method.

## Decision

Karyo is a **modular monolith**: one deployable Quarkus application, `:services:karyo-app`, that
aggregates the domain modules as libraries.

**Module structure is preserved, not abandoned.** Each domain remains an `api` / `core` pair:

```
services/{domain}-service/
├── karyo-{domain}-api/    # DTOs, SPI interfaces, event payloads — the contract
└── karyo-{domain}-core/   # entities, services, repositories, REST resources
```

The dependency rule is enforced by the build: **`core` depends on its own `api`, never on a
foreign `core`.** Cross-module contracts live exclusively in `api`. This is the microservices
boundary discipline retained without the microservices runtime.

> **Amendment, 2026-08-28 - three standing exceptions to that rule.** The rule as written above
> reads as absolute. It is not, and has not been for some time. Recorded here rather than left to
> be rediscovered from the build files.
>
> The real picture, extracted by grepping every `services/**/build.gradle.kts` for
> `project(":services:...-core")` edges that leave the module's own directory:
>
> | Module | Foreign `-core` modules it depends on | Recorded rationale |
> |---|---|---|
> | `karyo-ai-core` | 10 - product, inventory, layout, reporting, orders, fulfillment, webhooks, work, replenishment, stocktaking | Commented in the build file: *"Domain module deps - tool beans call these services in-process"*. The copilot's `@Tool` beans are thin wrappers over domain services; routing 22 tools through new `api` SPIs would have meant inventing a lookup interface per tool. |
> | `karyo-demo` | 8 - inventory, product, layout, orders, fulfillment, tasks, stocktaking, reporting | Commented in the build file: *"Deliberate cross-core coupling - karyo-demo is a prod-disabled leaf module that reuses the domain entities + repositories to construct backdated demo data."* It is 404'd out by `DemoEnabledFilter` unless `KARYO_DEMO=on` and never ships enabled. |
> | `karyo-monitors-core` | 1 - `karyo-replenishment-core` | **Not recorded.** See below. |
>
> Those are the only three. Every other `-core` in the repository depends on foreign `-api`
> modules alone; 18 of the 21 `api`/`core` pairs keep the rule exactly.
>
> **The monitors edge is an unexplained exception.** `karyo-monitors-core/build.gradle.kts`
> declares `implementation(project(":services:replenishment-service:karyo-replenishment-core"))`
> under the comment `// Cross-module read sources (public services in these cores)`. That labels
> the edge but does not justify it: it does not say why the read could not go through
> `karyo-replenishment-api`, which the same block also depends on. The single consumer is
> `BinBelowReorderDetector`, which calls `ReplenishmentService.needs(clientId)` and counts the
> results with `belowMin` set; `karyo-replenishment-api` contains only `ReplenishmentDtos.kt` and
> `ReplenishmentStrategy.kt`, so there is no SPI it could have used instead. Whether that is a
> deliberate waiver on the `ai`/`demo` model or an omission nobody caught is **not recoverable
> from the repository**, and this amendment does not guess. Someone with the history should
> either add a rationale comment beside it or declare a lookup SPI in
> `karyo-replenishment-api` and drop the edge.
>
> **Treat all three as waivers, not as precedent.** A new `-core` on `-core` dependency needs an
> explicit justification in the build file at minimum, and if it is not a prod-disabled leaf or an
> in-process tool facade it probably wants an `api` SPI instead. The FOSS-declares /
> paid-implements trick - `OpenPickGuard`, `PurgeBlockerLookup`, `PickZoneLookup`, where the
> consuming module declares the interface in its own `api` and a module it must not depend on
> implements it - is the pattern that keeps the graph clean when the dependency would otherwise
> point the wrong way.

**Three runtime mechanisms collapse:**

| concern | before | after |
|---|---|---|
| cross-module calls | REST + HTTP clients | **SPI bean injection** (`ProductLookup`, `StockUnitLookup`, `ShipmentLookup`, `GoodsReceiptLookup`) |
| state-change notification | Kafka topics | **synchronous CDI events**, joining the caller's transaction |
| persistence | database-per-service | **one `karyo` schema**, per-module Flyway version ranges (V1xx inventory, V2xx product, V3xx layout, …) |

**Kafka is removed from the runtime.** The `outbox_events` table is retained as a dormant append
log — every state change still lands there — for future external publication, webhook relay, and
the AI "recent activity" feed. No relay process runs.

**Cross-module referential integrity stays loose.** Modules reference each other by ID with no
foreign keys across boundaries, exactly as when they were separate databases. Validation happens
through SPI lookups. This is what keeps a module extractable.

## Consequences

### Positive
- Fits the actual deployment target: 4 containers (`karyo-app`, `postgresql`, `keycloak`,
  `nginx`) instead of a cluster.
- Multi-module operations become single transactions. Sagas, compensation logic, and eventual
  consistency disappear from the critical path.
- Cross-module calls are compile-time checked. A broken contract is a build failure, not a
  runtime 500 discovered in staging.
- One schema means real foreign keys and joins *within* a module, and one migration history.
- Local development is a single `quarkusDev` with live reload, not an orchestration exercise.
- Startup under 5s and a ~1.5 GB memory ceiling — viable on constrained hardware.

### Negative
- No independent deployment or scaling per module. Everything releases together.
- A module can no longer be written in a different language.
- The boundary discipline is now **convention plus build config** rather than a network
  boundary. Nothing physically stops a `core` from reaching into another `core` — only the
  Gradle dependency graph and review.
- One process means one blast radius: a memory leak or a runaway query in any module affects
  all of them.

### Neutral
- Splitting a module back out is a real option, not a rewrite: the `api` contract, the ID-only
  references, and the outbox log are exactly the seams needed. The logical boundaries defined
  in the superseded ADRs would re-apply to whatever is extracted.
- Kafka can return without re-architecting — the outbox is already populated.

## Alternatives Considered

**Keep microservices, shrink the count.** Merge 9 services into 3-4. Rejected: it keeps the
entire distribution tax (network calls, sagas, separate databases, contract tests) while
surrendering most of the independence that justified it. The worst of both.

**Monolith with no module structure.** A single flat source tree. Rejected: it discards the
boundary work already done and makes future extraction genuinely impossible. The `api`/`core`
split costs little and preserves optionality.

**Serverless / functions.** Rejected: a stateful, transactional WMS with sub-50ms p50 targets and
on-premise deployment requirements is close to the worst fit for a functions runtime.

## Implementation Notes

- Cores are consumed as jars, so each **requires `META-INF/beans.xml`** or Quarkus ArC silently
  skips CDI bean discovery — producing an empty API surface with no error.
- Flyway runs with `out-of-order: true`. Per-module version ranges interleave, so a new low-range
  migration sorts before already-applied higher ranges on a long-lived database.
- The `allOpen` plugin must open `@Path`, `@ApplicationScoped`, `@RequestScoped`, `@Entity`,
  `@MappedSuperclass`, `@QuarkusTest` — Quarkus proxying requires it.
- Residue from the microservices era persists and must be actively hunted. `/api/internal/*` — a
  service-to-service REST router with zero production callers after the pivot — remained
  publicly routable until 2026-07-19, and one of its endpoints took its tenant scope from a
  caller-supplied query parameter. **A pivot strands old architecture rather than deleting it.**

## Related Decisions
- [ADR-002](ADR-002-quarkus-framework.md) Quarkus — unchanged; the aggregation host.
- [ADR-005](ADR-005-postgresql-database.md) PostgreSQL — unchanged; one schema instead of nine.
- [ADR-011](ADR-011-no-event-sourcing.md) No event sourcing — unchanged.
- [ADR-028](ADR-028-gradle-kotlin-dsl-build-tool.md) Gradle multi-module — the mechanism that
  enforces the `api`/`core` dependency direction.

## References
- `settings.gradle.kts` - the current Gradle module boundary.
- `services/karyo-app/build.gradle.kts` - the module aggregation that produces the deployable.
- Before this decision, the project planned separate service deployables and asynchronous
  integration. That topology was never shipped and its design records are historical only.

## Revision History
- 2026-07-19: Written retroactively to record the June 2026 pivot.
- 2026-08-28: Amended in place (Decision section) to record the three standing exceptions to the
  "`core` never depends on a foreign `core`" rule - `karyo-ai-core`, `karyo-demo`, and the
  unexplained `karyo-monitors-core` → `karyo-replenishment-core` edge. No decision changed; the
  rule is restated with the waivers that were already in the build.
