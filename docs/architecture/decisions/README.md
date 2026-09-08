# Architecture Decision Records

**Audience:** Developers and implementers. **Status reviewed:** 2026-09-08.

This is a retained **decision collection**, not a list of everything installed. "Accepted" in an
original ADR records the decision at that time; implementation notes may be historical or future
intent. Read the current disposition below before using a code/configuration example.
[ADR-037](ADR-037-modular-monolith.md) records the June 2026 runtime pivot. All 37 ADRs remain
available, including the nine in `superseded/`; this index does not change public selection.

## ADR index

| ADR | Decision | Current disposition / replacement |
|---|---|---|
| [001](superseded/ADR-001-microservices-architecture.md) | Microservices | Superseded by 037 |
| [002](ADR-002-quarkus-framework.md) | Quarkus | Framework retained; one app, not per-service memory/edge guarantees |
| [003](ADR-003-kotlin-java-strategy.md) | Kotlin / Java | Retained; current pins in version catalog, no GPL-source translation workflow |
| [004](superseded/ADR-004-database-per-service.md) | Database per service | Superseded by 037: one application schema |
| [005](ADR-005-postgresql-database.md) | PostgreSQL | Retained; pgvector, RLS and partitioning examples are not deployed schema facts |
| [006](superseded/ADR-006-kafka-event-bus.md) | Kafka | Superseded by 037; no build/runtime broker |
| [007](superseded/ADR-007-kubernetes-orchestration.md) | Kubernetes | Superseded in practice: supported deployment is Compose |
| [008](ADR-008-rest-sync-events-async.md) | REST / events | Partial: external REST retained, internal calls SPI/CDI; webhook relay active |
| [009](superseded/ADR-009-api-gateway-kong.md) | Kong | Superseded by 037; nginx, no API gateway product |
| [010](ADR-010-choreography-sagas.md) | Choreography sagas | Partial: no distributed saga runtime; inspect actual transaction/observer boundaries |
| [011](ADR-011-no-event-sourcing.md) | CRUD plus journal | Conclusion retained, Kafka premise obsolete; no replay-built domain state |
| [012](ADR-012-cqrs-reporting.md) | CQRS reporting | Partial: direct SQL views/queries, not Kafka projections |
| [013](ADR-013-redis-caffeine-caching.md) | Redis / Caffeine | Partial: Caffeine reference caches only; no stock cache or Redis service |
| [014](ADR-014-flyway-migrations.md) | Flyway | Retained mechanism: one schema, startup migration, interleaved ranges; applied bytes frozen |
| [015](ADR-015-langchain4j.md) | LangChain4j | Copilot implemented; RAG/embedding examples are future scope |
| [016](ADR-016-claude-api-llm.md) | Claude provider | Anthropic and Ollama optional; default off, no automatic provider fallback implied |
| [017](ADR-017-pgvector-for-vector-storage.md) | pgvector | Accepted future design, parked/unimplemented |
| [018](ADR-018-rag-architecture-for-ai-features.md) | RAG | Accepted future design, parked/unimplemented; copilot uses tools |
| [019](ADR-019-oauth2-oidc-with-keycloak.md) | Keycloak | Partial: mandatory provider, real client/role/bootstrap contract in DEPLOY |
| [020](ADR-020-multi-tenancy-strategy.md) | Multi-tenancy | Partial: silo deployment with goods-owner scope; explicit principal kind retained, no tenant-per-schema model |
| [021](superseded/ADR-021-mtls-for-service-communication.md) | mTLS | Superseded internal-service premise; no internal mTLS in standard Compose |
| [022](superseded/ADR-022-gitops-with-argocd.md) | ArgoCD | Superseded in practice; no GitOps cluster |
| [023](ADR-023-prometheus-grafana-observability.md) | Metrics / dashboards | Partial: metrics endpoint exists, collector/dashboard stack not deployed |
| [024](ADR-024-opentelemetry-distributed-tracing.md) | Tracing | Extension present, dev/prod tracing explicitly disabled |
| [025](ADR-025-structured-logging-json.md) | JSON logs / Loki | Intent retained; [observability](../observability-architecture.md#2-structured-logging) owns runtime status |
| [026](ADR-026-react-typescript-web-ui.md) | React / TypeScript | Implemented; current package files and routes supersede example versions/network topology |
| [027](ADR-027-pwa-for-mobile.md) | Floor PWA | Implemented at `/m/`; only selected operations queue offline, no universal offline workflow |
| [028](ADR-028-gradle-kotlin-dsl-build-tool.md) | Gradle Kotlin DSL | Implemented; wrapper/catalog/build graph supersede example versions/Jib commands |
| [029](ADR-029-quarkus-dev-services.md) | Dev Services | Partial: PostgreSQL/Keycloak intended; automatic Compose discovery needs actual login proof |
| [030](ADR-030-conventional-commits-semver.md) | Commits / SemVer | Convention retained; sample release/changelog automation is not installed merely by this decision |
| [031](superseded/ADR-031-strangler-fig-migration.md) | Strangler migration | Superseded; no supported in-place myWMS conversion |
| [032](superseded/ADR-032-dual-write-data-migration.md) | Dual-write migration | Superseded; no CDC/shadow-read migration tooling |
| [033](ADR-033-cross-docking-event-interceptor.md) | Cross-dock interceptor | Commercial capability implemented; original configuration/pre-hook sketch not the released API |
| [034](ADR-034-equipment-adapter-spi.md) | Equipment/WCS adapters | Accepted design, unimplemented; ordinary human transport tasks do exist |
| [035](ADR-035-wave-based-bulk-fulfillment.md) | Wave fulfillment | Commercial orchestration implemented; no observable ALLOCATING state; original future-phase text is historical |
| [036](ADR-036-strategy-driven-configuration.md) | Strategy configuration | Implemented family of patterns, not universal priority-chain selection for every SPI |
| [037](ADR-037-modular-monolith.md) | Modular monolith | Current runtime decision; later implementation notes qualify its original outbox/transaction claims |

## Current implementation references

- [App assembly](../../../services/karyo-app/build.gradle.kts),
  [settings](../../../settings.gradle.kts), [version catalog](../../../gradle/libs.versions.toml)
- [DEPLOY](../../../DEPLOY.md): real four-container runtime, identity bootstrap and maintenance
- [API standards](../api-standards.md): actual response/filter/event boundaries
- [Extensibility](../extensibility-architecture.md): real SPI declarations and build-time discovery
- [Observability](../observability-architecture.md): instrumentation vs deployment vs known gaps
- [Requirements](../../REQUIREMENTS.md): historical identifiers and current interpretation

Examples inside ADRs preserve the rationale and alternatives considered. They are not executable
cookbooks or evidence of performance, availability, privacy, compliance or security guarantees.
Changing a genuine decision needs an explicit new decision or amendment, not a silent wording fix.

## ADR template

1. Status and date: proposed, accepted, deprecated or superseded; name a replacement if applicable.
2. Context: the problem and evidence.
3. Decision: what was chosen, including constraints.
4. Consequences: positive, negative and neutral.
5. Alternatives considered, including why rejected.
6. Implementation notes, explicitly separating shipped behavior from intent.
7. Related decisions and revision history.

Do not renumber or erase retired ADRs. Update this index and add contextual implementation notes
when execution diverges; preserve the original design body and its decision provenance.
