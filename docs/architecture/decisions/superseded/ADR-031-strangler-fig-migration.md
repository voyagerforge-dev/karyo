# ADR-031: Strangler Fig Pattern for Migration

> **SUPERSEDED.** Superseded by [ADR-037: Modular Monolith](../ADR-037-modular-monolith.md) (2026-06).
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
Karyo WMS is a greenfield rewrite of the legacy myWMS monolith. The migration cannot happen overnight — myWMS is a production system running 24/7 warehouse operations. Any migration strategy must ensure:

- **Zero downtime**: Warehouses operate 24/7; even planned maintenance windows must be < 15 minutes
- **Gradual rollout**: Ability to migrate one service/feature at a time, not all at once
- **Rollback safety**: Any migration step can be reversed if issues are discovered
- **Data consistency**: Both systems must have consistent views of inventory, orders, and operational data during the transition
- **Tenant-level control**: In a multi-tenant environment, different tenants can migrate at different times
- **Validation period**: Each migrated service must prove itself in production before the legacy equivalent is decommissioned

The myWMS codebase contains 10,000+ lines of battle-tested business logic across 10 major workflows (receiving, putaway, picking, shipping, replenishment, stocktaking, inventory management, location finding, order processing, state management). Rewriting all of this simultaneously and doing a big-bang cutover is high-risk.

## Decision
We will use the **Strangler Fig pattern** to incrementally migrate from myWMS to Karyo, with the API gateway routing traffic to the appropriate system based on the migration phase and feature flags.

**Migration Architecture:**

```
┌──────────────┐
│   Clients    │  (Web UI, Mobile PWA, External Integrations)
└──────┬───────┘
       │
       ▼
┌──────────────────────────────────────────┐
│            API Gateway                     │
│  ┌──────────────────────────────────────┐ │
│  │       Feature Flag Router            │ │
│  │                                      │ │
│  │  /api/v1/stock-units/* ──► Karyo     │ │  ← Phase 2: inventory migrated
│  │  /api/v1/orders/*      ──► myWMS     │ │  ← Phase 2: orders still on legacy
│  │  /api/v1/locations/*   ──► Karyo     │ │  ← Phase 2: layout migrated
│  │                                      │ │
│  │  Tenant 42: orders/* ──► Karyo       │ │  ← Tenant-level feature flags
│  │  Tenant 55: orders/* ──► myWMS       │ │
│  └──────────────────────────────────────┘ │
└─────────┬────────────────────┬────────────┘
          │                    │
     ┌────▼────┐          ┌───▼─────┐
     │  Karyo  │          │  myWMS  │
     │Services │          │Monolith │
     └─────────┘          └─────────┘
```

**Migration Phases:**

### Phase 1: Coexistence (New Features in Karyo)
- **Duration**: Months 1-5 (aligns with Karyo Phase 0-1)
- New features and APIs built exclusively in Karyo services
- myWMS continues handling all existing operations unchanged
- No traffic routing changes — Karyo handles only net-new endpoints
- Data: Karyo reads from myWMS via integration events or API calls for cross-reference data

### Phase 2: Service-by-Service Migration
- **Duration**: Months 6-9 (aligns with Karyo Phase 2-3)
- **Migration order** (based on dependency analysis and risk):
  1. **product-service** (lowest risk, read-heavy, few dependencies)
  2. **warehouse-layout-service** (read-heavy, foundational for other services)
  3. **inventory-service** (core, high dependency but well-defined boundaries)
  4. **order-service** (most complex, migrated last among core services)
  5. **task-service** (depends on inventory and layout, migrated with or after orders)
- For each service:
  - Build Karyo service equivalent with full feature parity
  - Deploy Karyo service alongside myWMS
  - Enable dual-write (ADR-032) for data consistency
  - Route read traffic to Karyo (shadow mode — validate against myWMS results)
  - Route write traffic to Karyo once validation passes
  - Monitor for 2-4 weeks before proceeding to next service

### Phase 3: Shadow Read Validation
- For each migrated service, run shadow reads:
  ```
  Client Request → API Gateway
       │
       ├──► Karyo (primary response returned to client)
       │
       └──► myWMS (shadow read, response discarded but compared)

  Comparator logs discrepancies between Karyo and myWMS responses
  ```
- Discrepancy threshold: < 0.1% of requests must differ to proceed
- Validation period: minimum 2 weeks per service at full production load

### Phase 4: Primary Switch
- Karyo becomes the primary system for migrated services
- myWMS continues as a fallback (traffic can be rerouted back via feature flag)
- Dual-write continues to keep myWMS data current
- Rollback capability: feature flag switches traffic back to myWMS within seconds

### Phase 5: Decommission
- After 4+ weeks of Karyo running as primary with no rollback:
  - Stop dual-write to myWMS
  - Archive myWMS database
  - Decommission myWMS application servers
  - Remove legacy routing rules from API gateway

**Feature Flags for Migration Control:**

```yaml
# Feature flag configuration (LaunchDarkly, Unleash, or config-based)
migration:
  services:
    inventory:
      read_source: "karyo"          # "mywms" | "karyo" | "shadow"
      write_target: "karyo"         # "mywms" | "karyo" | "dual"
      shadow_validation: false      # Enable shadow read comparison
      tenants:
        - id: 42
          override_read: "karyo"    # Tenant-specific override
        - id: 55
          override_read: "mywms"    # This tenant stays on legacy longer
    orders:
      read_source: "mywms"
      write_target: "mywms"
      shadow_validation: false
```

**Canary Deployment per Tenant:**

```
Tenant Migration Timeline:

Tenant 42 (pilot):  ─── mywms ───┤── shadow ──┤── karyo primary ──►
Tenant 55 (early):  ─── mywms ──────────┤── shadow ──┤── karyo ──►
Tenant 78 (main):   ─── mywms ────────────────────┤── shadow ─┤── karyo ──►

                    Month 1      Month 2      Month 3      Month 4
```

## Consequences

### Positive
- Zero-downtime migration — the warehouse never stops operating; traffic shifts gradually between systems
- Rollback at any point — feature flags can route traffic back to myWMS within seconds if issues are discovered
- Tenant-level migration control — pilot tenants can validate Karyo before rolling out to all tenants
- Risk is bounded — each service is migrated and validated independently; a problem with order migration does not affect already-migrated inventory service
- Shadow read validation provides production-grade confidence before committing to the new system
- Battle-tested myWMS logic serves as the reference implementation and validation oracle during migration
- Development teams can focus on one service migration at a time, reducing context switching

### Negative
- Extended period of maintaining two systems simultaneously — both myWMS and Karyo must be kept running, monitored, and potentially patched
- Dual-write complexity (ADR-032) — keeping data consistent between two systems during transition is technically challenging
- API gateway routing complexity — feature flags and tenant-specific routing add configuration that must be carefully managed
- Shadow read comparisons require semantic understanding of response differences (e.g., different field names, different date formats) — simple byte comparison is insufficient
- Migration timeline is longer than a big-bang approach — the full migration may take 6-12 months
- Testing burden increases during transition — both systems must be tested for each deployment

### Neutral
- The API gateway (Kong/Envoy) must support header-based and path-based routing with feature flag integration; this is standard functionality for modern API gateways
- myWMS may need minor modifications to support coexistence (e.g., exposing additional events or APIs for Karyo to consume during transition)
- The migration order (product → layout → inventory → orders → tasks) can be adjusted based on business priorities or technical dependencies

## Alternatives Considered

### Alternative 1: Big-Bang Migration
- **Pros**: Simplest conceptually — build the complete system, test it, switch over in a single maintenance window. No dual-write complexity, no shadow reads, no feature flags. Once done, no legacy system to maintain.
- **Cons**: Extremely high risk — requires full feature parity across all 9 services and 10 major workflows before any migration can begin. A single bug discovered post-migration affects all tenants simultaneously. Rollback requires reverting to a potentially stale myWMS database. Extended maintenance window (hours to days) for data migration. The team cannot deliver value incrementally — the entire rewrite must be complete before any benefit is realized.
- **Why rejected**: The risk is unacceptable for a 24/7 warehouse operation. A single critical bug during cutover could halt warehouse operations for all tenants. The time to full feature parity (12+ months) means no value delivery until the entire project is complete.

### Alternative 2: Parallel Run from Day 1 (Active-Active)
- **Pros**: Both systems process all requests simultaneously from the beginning, providing immediate validation. No routing complexity — both systems always receive all traffic.
- **Cons**: Extreme complexity — two systems must process every operation identically in real-time. Conflict resolution for divergent results is nearly impossible. Double the infrastructure cost from day 1. Write conflicts (both systems modify the same data) require complex reconciliation. Not practical for stateful warehouse operations where a pick confirmation must update exactly one system's stock level.
- **Why rejected**: Active-active is impractical for a stateful system like a WMS where operations modify inventory state. Two systems independently processing the same pick order would double-decrement stock. The reconciliation complexity far exceeds the benefits.

## Implementation Notes
- Implement the feature flag router as a middleware in the API gateway (Kong plugin or Envoy filter) that reads migration configuration from a ConfigMap or external feature flag service
- Build a shadow read comparator service that:
  - Receives responses from both Karyo and myWMS
  - Normalizes field names, date formats, and ordering differences
  - Logs discrepancies with full context (request, both responses, diff)
  - Publishes discrepancy metrics to Prometheus (ADR-023) for dashboard monitoring
- Create a migration runbook per service documenting: pre-migration checklist, feature flag changes, validation criteria, rollback procedure, post-migration cleanup
- During Phase 2, myWMS may need to emit CDC events (via Debezium, ADR-032) so Karyo services can maintain read copies of data they need from still-legacy services
- Plan the pilot tenant selection carefully — choose a tenant with representative workload but tolerance for minor issues
- Implement circuit breakers on the shadow read path so that myWMS slowness does not affect Karyo response times

## Related Decisions
- [ADR-032](ADR-032-dual-write-data-migration.md): Dual-Write Pattern — data consistency mechanism during the strangler fig transition
- [ADR-022](ADR-022-gitops-with-argocd.md): GitOps — migration feature flags managed declaratively
- [ADR-020](../ADR-020-multi-tenancy-strategy.md): Multi-Tenancy — tenant-level migration control leverages the existing tenant isolation model

## References
- [Martin Fowler: Strangler Fig Application](https://martinfowler.com/bliki/StranglerFigApplication.html)
- [Sam Newman: Monolith to Microservices](https://samnewman.io/books/monolith-to-microservices/)
- [Feature Toggles (Feature Flags)](https://martinfowler.com/articles/feature-toggles.html)
- [Canary Releases (Martin Fowler)](https://martinfowler.com/bliki/CanaryRelease.html)

## Revision History
- 2026-02-15: Initial version
