# Karyo WMS — Requirements

> **Post-pivot note (2026-07-19).** Entries below are a historical record of work as it was
> completed. Some describe mechanisms the June 2026 pivot replaced — Kafka audit events, "all 4 services",
> the Keycloak Kafka SPI. Those items were genuinely delivered at the time; they are not current
> architecture. See [ADR-037](architecture/decisions/ADR-037-modular-monolith.md).


**Core Value:** A potential customer can try the system themselves — clicking through a
guided receive → putaway → pick → ship flow that demonstrates real warehouse operations
with real data moving through real services.

> Migrated from the retired planning register on 2026-06-11. The original requirement IDs,
> checkboxes and wording below are retained as milestone history, not a current capability or
> automated-test certification. The old v2/out-of-scope sections are also dated scope, not the
> present backlog. Acceptance then does not mean the same mechanism exists now.

## Present-day interpretation (2026-09-08)

| Original requirement family | Current disposition / evidence owner |
|---|---|
| INFRA-02, AUTH-06, TEST-01/02/06 | No Kafka runtime or companion dependency. CDI handles internal notifications; outbox feeds the active webhook relay. Authentication audit polls Keycloak. See [ADR index](architecture/decisions/README.md). |
| AUTH-03/04, DASH-01 | Silo instance with goods-owner scope, not a login tenant picker or every query hard-bound to one claim. OPS and OWNER have distinct scopes; roles are independent. See [DEPLOY](../DEPLOY.md). |
| DASH-10 | Guided tour retired; optional synthetic demo generator is not a production prerequisite. |
| DEPLOY/CLOUD/IDEMPOTENT | One application, four Compose containers; current flags, ports, memory settings, bootstrap and maintenance are owned by [DEPLOY](../DEPLOY.md), not the historical row values. |
| TEST-03/04/05/07 | Playwright, Gatling and Pact have different invocation/prerequisite scopes. Harness existence or a historical checkmark does not prove current performance baselines or all tests executing in CI. Use the actual runner configuration and workflow. |
| DASH-11/12 | KPI dashboard and zone-grid occupancy view implemented; a physical-coordinate 2D floor plan is not implied. |
| LYOT-09 | Location finder implemented in-process, not through an internal REST API. See [location finder](functional/location-finder.md). |
| Mobile / analytics / natural-language scope below | Floor PWA, KPI reporting, optional copilot and paid advisory engines now exist. Their earlier out-of-scope status applies to the original milestone only. RAG/document AI remain unimplemented. |
| Performance, availability and security targets | Requirements to verify, not measured guarantees conferred by this register. |

This complete register remains part of the selected documentation, with historical context.
New product/release decisions are not silently inferred from the old checkboxes.

## v1 Requirements - historical completion record

### Infrastructure
- [x] **INFRA-01**: Shared library extraction — BaseEntity, TenantEntity (Hibernate @FilterDef/@Filter) to `libs/karyo-common`; TenantContext, TenantFilter (MDC) to `libs/karyo-security`
- [x] **INFRA-02**: Shared event infrastructure — DomainEvent<T> envelope, OutboxEvent, OutboxService, OutboxEventRepository to `libs/karyo-events`. OutboxProcessor stays per-service
- [x] **INFRA-03**: Shared exception handling — ProblemDetail (7-field RFC 7807), ConstraintViolationExceptionMapper, KaryoException to `libs/karyo-common`
- [x] **INFRA-04**: Gradle build conventions — two-tier convention plugins (karyo.kotlin-conventions + karyo.quarkus-service), allOpen 6 annotations, deps explicit per service
- [x] **INFRA-05**: Unified Keycloak realm — single realm config with all service clients and predefined roles

### Authentication
- [x] **AUTH-01**: Log in with username/password → JWT access + refresh tokens
- [x] **AUTH-02**: RBAC with 7 roles (ADMIN, MANAGER, OPERATOR, RECEIVER, VIEWER, INTEGRATOR, AI_SERVICE)
- [x] **AUTH-03**: Tenant data isolation — every query scoped to JWT clientId claim
- [x] **AUTH-04**: Admin can create, deactivate, assign roles to users per tenant
- [x] **AUTH-05**: Account lockout after 5 failed logins for 30 minutes
- [x] **AUTH-06**: Auth events (login/logout/role changes) published to Kafka audit trail

### Warehouse Layout
- [x] **LYOT-01**: Storage locations with hierarchy (Zone > Area > Cluster > Location)
- [x] **LYOT-02**: Zones with usage types (receiving, storage, picking, shipping, cross-dock)
- [x] **LYOT-03**: Location types with physical dimensions and weight/lifting capacity
- [x] **LYOT-04**: Lock/unlock locations for stocktaking, quarantine, damage
- [x] **LYOT-05**: Resolve location by barcode/scan code in under 50ms
- [x] **LYOT-06**: Configure storage strategies (mix rules, zone preferences)
- [x] **LYOT-07**: Track location allocation percentage via inventory-service events
- [x] **LYOT-08**: Fix assignments (product-to-location with min/max amounts)

### Product Master Data
- [x] **PROD-01**: Create/manage products (SKU, name, description, dimensions, weight)
- [x] **PROD-02**: Multiple barcodes per product (EAN, UPC, supplier codes)
- [x] **PROD-03**: Units of measure (pieces, kg, liters, boxes, pallets)
- [x] **PROD-04**: Packaging units with conversion factors (inner/outer/pallet)
- [x] **PROD-05**: Lot/shelf-life tracking per product
- [x] **PROD-06**: Product state (active/inactive). _Note: implemented as bidirectional transitions per phase CONTEXT override, not forward-only as originally worded._
- [x] **PROD-07**: Serial number tracking mode per product (none/receipt/always)

### Dashboard
- [x] **DASH-01**: Polished login screen with tenant selection
- [x] **DASH-02**: Sidebar navigation with role-based visibility per section
- [x] **DASH-03**: Locations screen (list, create, edit, hierarchy view)
- [x] **DASH-04**: Products catalog screen (list, create, edit, barcode management)
- [x] **DASH-05**: Inventory overview (stock by location and product, filterable)
- [x] **DASH-06**: Admin user + role management screen
- [x] **DASH-07**: Responsive, usable on tablets (min 768px)
- [x] **DASH-08**: User-friendly error messages (RFC 7807 → readable notifications)
- [x] **DASH-09**: Dark mode toggle
- [x] **DASH-10**: Guided walkthrough demo (receive > putaway > pick > ship with sample data)

### Deployment (Phase 06.1)
- [x] **DEPLOY-01**: Production config profiles with env var substitution (DB, Kafka, OIDC) for all 4 services
- [x] **DEPLOY-02**: SPA loads Keycloak URL at runtime via nginx envsubst — no rebuild on domain change
- [x] **DEPLOY-03**: Shared Dockerfile template with build args supports all services
- [x] **DEPLOY-04**: Nginx routes API paths to backends, /auth to Keycloak, / to SPA
- [x] **DEPLOY-05**: Single idempotent deploy script for supported Compose deployments

### Cloud Deployment (Phase 06.2)
- [x] **CLOUD-01**: Deploy script auto-detects Docker or Podman
- [x] **CLOUD-02**: Container mem_limits driven by .env.prod with backward-compatible defaults
- [x] **CLOUD-03**: JVM heap sizes configurable per environment via JAVA_OPTS, no image rebuild
- [x] **CLOUD-04**: Cloud .env.prod allocates for 24GB RAM (768MB/Quarkus svc, 2GB Keycloak, 2GB PostgreSQL)
- [x] **CLOUD-05**: Oracle Cloud guide (VM creation, Docker install, Cloudflare Tunnel, deploy)

### Deployment Idempotency (Phase 8)
- [x] **IDEMPOTENT-01**: Deploy script `--quick`, `--reset-db`, `--help` flags
- [x] **IDEMPOTENT-02**: Validates required .env.prod vars before starting; fails early on CHANGE_ME/empty
- [x] **IDEMPOTENT-03**: Checks ports 80, 5432, 9092 for conflicts; reports holding process
- [x] **IDEMPOTENT-04**: On health-check timeout, prints last 20 lines of failing container logs
- **IDEMPOTENT-05 (retired)**: Automatic post-build image pruning; [DEPLOY](../DEPLOY.md#select-the-deployment) owns the current cleanup policy.
- [x] **IDEMPOTENT-06**: DEPLOY.md documents prerequisites, env, flags, troubleshooting

### Enhanced Testing Layer (Phase 9)
- [x] **TEST-01**: Kafka Companion producer tests (4 services) verify OutboxProcessor → correct topics
- [x] **TEST-02**: Companion tests for active consumers (ProductStateChanged, TransportOrderCompleted, StocktakingCompleted, UnitLoadTransferred)
- [x] **TEST-03**: Playwright E2E for walkthrough, auth (real Keycloak), CRUD, navigation
- [x] **TEST-04**: Gatling HTML baselines (stock selection, list, mutation, walkthrough) × 3 load profiles
- [x] **TEST-05**: Pact provider verification in all 4 services (loads from Pact Broker)
- [x] **TEST-06**: Consumer contracts (layout REST → inventory+product, inventory Kafka consumer, frontend → 4 APIs)
- [x] **TEST-07**: Self-hosted Pact Broker in dev infra + CI gate on contract violations

> These are the original Phase 9 completion claims. The current automated path does not run
> the retired Kafka suites or establish the listed Gatling baselines; see the interpretation above.

## v2 Requirements (original forward backlog, not current status)

### Dashboard
- **DASH-11**: 2D warehouse map visualization with zone coloring and occupancy heatmap
- **DASH-12**: Real-time KPI dashboard (inventory accuracy, utilization %, throughput)
- **DASH-13**: Barcode scanning simulation in browser for remote demos
- **DASH-14**: Stock selection algorithm preview (show FIFO pass reasoning)
- **DASH-15**: Configurable dashboard widgets (drag-and-drop layout)

### Layout
- **LYOT-09**: Location finder algorithm (19-filter, internal API for task-service)
- **LYOT-10**: Location finder algorithm visualization (filter passes narrowing candidates)

### Product
- **PROD-08**: Product full-text search across SKU, name, description

### Auth
- **AUTH-07**: Keycloak OIDC bridge for enterprise federated identity
- **AUTH-08**: OAuth social login (Google, GitHub)

## Out of Scope (original milestone only)

The table preserves the original reasons, including options later implemented or superseded.
It is not today's product exclusion list.

| Feature | Reason |
|---------|--------|
| Full Keycloak admin UI integration | Huge surface area; lightweight JWT sufficient for demo |
| Mobile PWA | Doubles frontend work; mobile is Phase 3 |
| Real-time WebSocket for everything | Over-engineering; polling sufficient for demo |
| Full ERP integration | Customer-specific, needs their test environment |
| Full reporting/analytics suite | Pre-built KPIs + CSV export later |
| Zone optimization / AI slotting | Needs months of historical data |
| Custom workflow engine / BPMN | ADR-010 chose choreography sagas |
| Multi-language i18n | English-only for demo |
| Offline-first web dashboard | Edge/mobile concern, not web |
| Natural language search | Requires AI service (Phase 3) |
