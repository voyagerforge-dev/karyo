# ADR-020: Multi-Tenancy Strategy (Row-Level Isolation)

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md).
>
> The row-level SaaS strategy is superseded by **silo tenancy** (one instance per company;
> `client_id` = goods owner). Still in force: the Implementation Notes ruling that the system
> principal must be identified by an explicit **JWT claim, not a magic tenant ID** - implemented
> 2026-07-19 as `principal_kind` in `PrincipalKind` and `TenantFilter`. The surviving rule is also
> retained in this ADR's Implementation Notes.


## Status
Partially superseded

## Context
Karyo WMS must support multi-tenant operations, particularly for 3PL (Third-Party Logistics) providers who manage warehouse operations for multiple clients within a single warehouse or across multiple warehouses. The legacy myWMS system uses a `Client` entity with application-level filtering — all tenant-scoped entities inherit from a base class that includes a `client` reference, and queries are filtered by the current user's client.

Multi-tenancy in Karyo must address:
- **Data isolation**: Tenant A must never see tenant B's inventory, orders, or operational data
- **Performance**: Tenant isolation must not significantly degrade query performance
- **Migration compatibility**: The existing myWMS `Client` model maps to Karyo's tenant concept
- **Cross-tenant operations**: System administrators may need cross-tenant visibility (e.g., for 3PL operators managing multiple clients)
- **Deployment flexibility**: Both shared infrastructure (cost-efficient for SaaS) and dedicated infrastructure (required by some enterprise customers) must be supported
- **Edge deployments**: Multi-tenancy must work on resource-constrained edge nodes

The myWMS codebase handles multi-tenancy through a specific pattern: all queries check `client_id`, with a fallback chain of `order client -> system client -> any client` (see `PickingStockFinder` and other business services). This client fallback chain must be preserved in Karyo.

## Decision
We will implement **row-level isolation** with `tenant_id` (mapped from myWMS `client_id`) on all tenant-scoped database tables, enforced at both the application level and the database level.

**Application-Level Enforcement (Primary):**

```kotlin
// Quarkus CDI interceptor extracts tenantId from JWT and sets it in request context
@ApplicationScoped
class TenantContextInterceptor : ContainerRequestFilter {
    @Inject
    lateinit var tenantContext: TenantContext

    override fun filter(requestContext: ContainerRequestContext) {
        val jwt = requestContext.securityContext.userPrincipal as JsonWebToken
        tenantContext.tenantId = jwt.getClaim<Long>("tenantId")
        tenantContext.isSystemTenant = jwt.getClaim<Boolean>("isSystemTenant") ?: false
    }
}

// TenantContext is request-scoped
@RequestScoped
class TenantContext {
    var tenantId: Long = 0
    var isSystemTenant: Boolean = false
}

// Panache repository base class with automatic tenant filtering
abstract class TenantRepository<T : TenantEntity> : PanacheRepository<T> {
    @Inject
    lateinit var tenantContext: TenantContext

    fun findByIdForTenant(id: Long): T? {
        return find("id = ?1 AND client.id = ?2", id, tenantContext.tenantId).firstResult()
    }

    fun listForTenant(page: Int, size: Int): List<T> {
        return find("client.id", tenantContext.tenantId)
            .page(Page.of(page, size))
            .list()
    }

    // System tenant can query across all tenants (3PL admin use case)
    fun listAllTenants(page: Int, size: Int): List<T> {
        check(tenantContext.isSystemTenant) { "Only system tenant can query across tenants" }
        return findAll().page(Page.of(page, size)).list()
    }
}
```

**Database-Level Defense-in-Depth (Secondary):**

```sql
-- PostgreSQL Row Level Security as a second barrier
-- Applied to every tenant-scoped table

ALTER TABLE stock_units ENABLE ROW LEVEL SECURITY;

CREATE POLICY tenant_isolation_policy ON stock_units
    USING (tenant_id = current_setting('app.current_tenant_id')::bigint);

-- Application sets the session variable before each transaction
-- SET LOCAL app.current_tenant_id = '42';
```

**Kafka Multi-Tenancy:**
- Partition key format: `{tenantId}:{entityId}` — ensures all events for a tenant are ordered within a partition
- Consumer group per service instance; consumers filter events by tenantId from the event envelope
- Topic-per-service (not topic-per-tenant) to avoid topic proliferation

```kotlin
// Kafka event envelope includes tenantId
data class DomainEvent<T>(
    val eventId: String = UUID.randomUUID().toString(),
    val eventType: String,
    val timestamp: Instant = Instant.now(),
    val source: String,
    val tenantId: Long,  // Always present for tenant-scoped events
    val correlationId: String? = null,
    val payload: T,
)
```

**Cache Multi-Tenancy:**
- Cache key format: `{tenantId}:{entityType}:{entityId}`
- Redis: key prefix includes tenantId; TTL-based eviction (no cross-tenant cache pollution)
- Caffeine (L1): tenant-aware cache loader; separate cache regions per tenant if needed for large tenants

**myWMS Client Fallback Chain Preservation:**
The myWMS pattern where queries fall back from `order client -> system client -> any client` is preserved as a configurable behavior in Karyo:

```kotlin
// Reproduces myWMS client fallback chain
fun findStockForPicking(itemDataId: Long, clientId: Long): List<StockUnit> {
    // 1. Try exact client match
    var stock = stockRepo.findAvailableByItemAndClient(itemDataId, clientId)
    if (stock.isNotEmpty()) return stock

    // 2. Try system client
    val systemClientId = clientService.getSystemClientId()
    stock = stockRepo.findAvailableByItemAndClient(itemDataId, systemClientId)
    if (stock.isNotEmpty()) return stock

    // 3. If strategy allows, try any client (3PL shared stock)
    if (orderStrategy.allowCrossClientStock) {
        stock = stockRepo.findAvailableByItem(itemDataId)
    }
    return stock
}
```

## Consequences

### Positive
- Simplest deployment model — all tenants share the same database, Kafka cluster, and service instances, minimizing infrastructure cost
- Compatible with myWMS's existing `Client` entity model — straightforward migration of tenant data
- Two-layer enforcement (application + database RLS) provides defense-in-depth against data leaks
- Horizontal scaling is independent of tenant count — adding tenants does not require infrastructure changes
- Query performance is maintained through proper indexing on `tenant_id` columns
- Works on edge deployments without additional resource overhead

### Negative
- "Noisy neighbor" risk: one tenant's heavy queries can affect other tenants' performance on shared infrastructure
- Row-level security adds a small overhead to every query (PostgreSQL checks the RLS policy on each row access)
- Cross-tenant reporting or aggregation requires special handling (system tenant role, separate reporting queries)
- Data migration for a single tenant (e.g., moving a client off the platform) requires careful row-level extraction
- Testing must explicitly verify tenant isolation — every query path must be tested for cross-tenant leaks

### Neutral
- Large enterprise customers who require dedicated infrastructure can still get database-per-tenant by deploying a dedicated Karyo instance — the row-level isolation works identically whether shared or dedicated
- The RLS policy approach can be replaced with schema-per-tenant in the future if needed, as the application-level filtering remains the primary enforcement mechanism
- Kafka partition assignment may need rebalancing as tenant distribution changes over time

## Alternatives Considered

### Alternative 1: Schema-Per-Tenant
- **Pros**: Stronger isolation at the database level, independent schema evolution per tenant, easier per-tenant backup and restore, no risk of cross-tenant data leaks at the query level
- **Cons**: Flyway migration complexity — every schema change must be applied to all tenant schemas, schema creation/deletion overhead for tenant onboarding/offboarding, connection pool management becomes complex (one pool per schema or dynamic schema switching), does not scale well beyond ~100 tenants
- **Why rejected**: Migration complexity is the primary concern. With 9 microservices each having their own database, managing N schemas per database per service (9 * N total schemas) makes Flyway migrations operationally challenging. The myWMS migration already uses a single-schema model with client filtering, and changing this adds unnecessary risk.

### Alternative 2: Database-Per-Tenant
- **Pros**: Strongest isolation, independent scaling per tenant, per-tenant backup/restore, meets the strictest compliance requirements, no noisy neighbor
- **Cons**: Infrastructure cost scales linearly with tenant count (9 databases * N tenants), connection pool exhaustion at scale, cross-tenant queries require federated queries or separate aggregation, dynamic database provisioning adds deployment complexity, impractical for edge deployments
- **Why rejected**: Operational overhead is prohibitive. With 9 microservices, each tenant would require 9 separate databases. For a 3PL provider with 50 clients, that's 450 database instances. This contradicts the edge deployment goal (< 2GB RAM) and the cost-efficiency goal.

### Alternative 3: Shared Tables Without RLS (Application-Only Enforcement)
- **Pros**: Simplest implementation, no database-level configuration needed, fastest query performance (no RLS overhead)
- **Cons**: Single point of failure — a bug in the application-level filtering code could expose one tenant's data to another. No defense-in-depth. Violates security best practices for multi-tenant SaaS applications. Regulatory risk for customers in regulated industries.
- **Why rejected**: Unacceptable security risk. A single missed `WHERE tenant_id = ?` clause could expose sensitive warehouse data across tenants. Database-level RLS provides a safety net that catches application-level bugs.

## Implementation Notes
- Add `tenant_id BIGINT NOT NULL` column to every tenant-scoped table in every service's Flyway migrations; create a composite index `(tenant_id, <primary_query_columns>)` for common query patterns
- Implement a `@TenantScoped` annotation for Panache repositories that automatically adds tenant filtering; use a Quarkus CDI interceptor to enforce this
- The PostgreSQL RLS policy requires setting `app.current_tenant_id` as a session variable at the beginning of each transaction; implement this in a Quarkus `@Transactional` interceptor
- For Kafka consumers, implement a `TenantAwareConsumer` base class that extracts `tenantId` from the event envelope and sets it in the processing context before invoking business logic
- Write integration tests that explicitly verify tenant isolation: create data for tenant A, query as tenant B, assert empty results
- The system tenant (3PL admin) should be identified by a special `isSystemTenant` claim in the JWT, not by a magic tenant ID value
- For the myWMS migration, map the existing `Client.id` values to `tenant_id` values 1:1 to preserve referential integrity

## Related Decisions
- [ADR-019](ADR-019-oauth2-oidc-with-keycloak.md): OAuth2/OIDC with Keycloak — tenantId is carried in JWT tokens
- [ADR-004](superseded/ADR-004-database-per-service.md): Database-per-Service Pattern — each service's database applies RLS independently
- [ADR-006](superseded/ADR-006-kafka-event-bus.md): Apache Kafka for Event Bus — tenant isolation in event streaming
- [ADR-017](ADR-017-pgvector-for-vector-storage.md): pgvector — vector search filtered by tenant_id

## References
- [PostgreSQL Row Level Security Documentation](https://www.postgresql.org/docs/current/ddl-rowsecurity.html)
- [Multi-Tenant SaaS Patterns (Microsoft Azure Architecture)](https://learn.microsoft.com/en-us/azure/architecture/guide/multitenant/overview)
- [Quarkus Multi-Tenancy Guide](https://quarkus.io/guides/hibernate-orm#multitenancy)

## Revision History
- 2026-02-15: Initial version
