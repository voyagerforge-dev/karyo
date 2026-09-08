# ADR-013: Redis for Distributed Caching + Caffeine for Local Caching

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md) — Half implemented, and the missing half is no longer motivated.
>
> **Caffeine L1 is real** — `UnitLoadType` is cached with a 60-minute TTL, and the ADR-013 rule that
> `StockUnit` is **never** cached still holds and is still important.
>
> **Redis L2 was never deployed and is not planned.** Its rationale was sharing reference data between
> separate service processes; there is one process, so an in-process cache is sufficient. There is no
> Redis container in the stack.


## Status
Partially superseded

## Context
Karyo WMS microservices frequently query reference data from other services:

- **Product data:** Inventory-service, order-service, and task-service all need product names, lot requirements, and shelf-life rules from product-service.
- **Location data:** Inventory-service, order-service, and task-service need location names, area usages, and zone information from warehouse-layout-service.
- **Strategy data:** Order-service needs OrderStrategy configuration; task-service needs StorageStrategy.
- **User/session data:** Auth-service issues JWT tokens; services need to check token blacklist (logout).

Without caching, every stock selection (13-pass algorithm in inventory-service) would require multiple REST calls to product-service and layout-service for each pass. At 10K orders/hour with multiple picks per order, this creates an unsustainable volume of cross-service REST calls.

Key constraints:
- **StockUnit must NOT be cached:** Stock amounts change with every pick, reservation, and transfer. Serving stale stock data could cause double-picks or phantom inventory. This is the most critical business rule for caching.
- **Edge deployment:** Cache infrastructure must fit within the < 2GB RAM budget.
- **Multi-instance services:** In cloud deployment, services run multiple replicas. In-process caches are per-instance and can serve stale data after an event-driven invalidation reaches one instance but not others. A distributed cache ensures all instances see the same state.

## Decision
We will use a **two-level caching strategy:**

- **L1: Caffeine (in-process)** for frequently accessed, rarely changing data with short-to-medium TTL
- **L2: Redis 7+ (distributed)** for shared data across service instances, session management, and distributed locks

### Cache Configuration

| Data Type | L1 (Caffeine) | L2 (Redis) | TTL | Invalidation | Rationale |
|-----------|--------------|------------|-----|-------------|-----------|
| ItemData (products) | 60 min, max 10K entries | 60 min | 60 min | Event: `item-data.updated` | Read-heavy, rarely changes |
| StorageLocation | 30 min, max 50K entries | 30 min | 30 min | Event: `location.lock-changed`, `location.allocation-changed` | Read-heavy, allocation changes need timely propagation |
| UnitLoadType | 60 min, max 100 entries | 60 min | 60 min | Event: `unit-load-type.updated` (rare) | Almost never changes |
| OrderStrategy | 60 min, max 50 entries | 60 min | 60 min | Event: `order-strategy.updated` (rare) | Configuration data |
| StorageStrategy | 60 min, max 50 entries | 60 min | 60 min | Event: `storage-strategy.updated` (rare) | Configuration data |
| Area / Zone | 60 min, max 100 entries | 60 min | 60 min | Service restart | Almost never changes |
| **StockUnit** | **NO CACHE** | **NO CACHE** | N/A | N/A | **High write rate, consistency critical** |
| **UnitLoad** | **NO CACHE** | **NO CACHE** | N/A | N/A | **High write rate (state, location changes)** |
| User sessions | None | 30 min | 30 min | On logout (explicit delete) | Shared across instances |
| Location weight sums | None | 5 min | 5 min | Event + TTL | Expensive aggregate, tolerate slight staleness |
| Rate limiting counters | None | Per-window | Sliding window | Auto-expire | Gateway rate limiting |
| Distributed locks | None | Per-lock | 30 sec | Auto-expire | Location reservation deduplication |

### Cache-Aside Pattern

```kotlin
@ApplicationScoped
class ProductCacheService(
    @RestClient private val productClient: ProductServiceClient,
    private val caffeineCache: Cache<String, ItemData>,  // L1
    private val redisClient: RedisClient,                 // L2
) {
    fun getProduct(itemNumber: String): ItemData {
        // L1: Check Caffeine (in-process)
        caffeineCache.getIfPresent(itemNumber)?.let { return it }

        // L2: Check Redis (distributed)
        val redisKey = "product:$itemNumber"
        redisClient.get(redisKey)?.let { json ->
            val product = deserialize(json)
            caffeineCache.put(itemNumber, product)  // Populate L1
            return product
        }

        // Miss: Fetch from product-service
        val product = productClient.getByNumber(itemNumber)
        redisClient.setex(redisKey, 3600, serialize(product))  // Populate L2
        caffeineCache.put(itemNumber, product)                   // Populate L1
        return product
    }
}
```

### Event-Driven Invalidation

```kotlin
@ApplicationScoped
class ProductCacheInvalidator(
    private val caffeineCache: Cache<String, ItemData>,
    private val redisClient: RedisClient,
) {
    @Incoming("karyo-product-item-data-updated")
    fun onProductUpdated(event: DomainEvent<ItemDataUpdatedEvent>) {
        val itemNumber = event.payload.itemNumber
        caffeineCache.invalidate(itemNumber)                    // Invalidate L1
        redisClient.del("product:$itemNumber")                   // Invalidate L2
    }
}
```

**Invalidation flow:**
1. Product-service publishes `item-data.updated` event to Kafka
2. Each consuming service's cache invalidator receives the event
3. Invalidator removes the entry from both L1 (Caffeine) and L2 (Redis)
4. Next access triggers a cache miss and fresh data is loaded

**TTL as safety net:** Even if an invalidation event is missed (unlikely with Kafka's durability), the TTL ensures stale data expires within 30-60 minutes.

### Redis Additional Uses

| Use Case | Data Structure | TTL | Service |
|----------|---------------|-----|---------|
| User sessions | String (JSON) | 30 min | auth-service |
| Token blacklist (logout) | Set | Match token expiry | auth-service |
| Distributed locks | String + SETNX | 30 sec | task-service (location reservation dedup) |
| Rate limiting | Sorted set (sliding window) | Per window | API Gateway (Kong Redis plugin) |
| Location weight aggregates | String (JSON) | 5 min | warehouse-layout-service |

### Edge Deployment

Redis at edge: **64 MB RAM** (single instance, no replication)
- Sessions for a single warehouse's operators (< 100 concurrent)
- Cache data for 6 core services
- No persistence needed at edge (cache can be rebuilt from services on restart)
- Redis configuration: `maxmemory 64mb`, `maxmemory-policy allkeys-lru`

## Consequences

### Positive
- **Dramatic reduction in cross-service calls:** Product and location data (the most frequently queried reference data) are served from L1 cache in < 1ms, eliminating thousands of REST calls per hour during picking operations.
- **Two-level protection:** L1 (Caffeine) provides sub-microsecond access for hot data. L2 (Redis) provides millisecond access shared across all service instances. Only on double-miss does a REST call to the source service occur.
- **Event-driven freshness:** Cache invalidation via Kafka events ensures caches are updated within seconds of data changes, not waiting for TTL expiry.
- **TTL safety net:** Even without event-driven invalidation, data staleness is bounded by TTL (30-60 minutes for most data types).
- **No cache for critical data:** The explicit decision to NOT cache StockUnit and UnitLoad prevents the most dangerous class of caching bugs (serving stale stock levels).
- **Redis versatility:** Beyond caching, Redis handles sessions, distributed locks, and rate limiting — three additional use cases from a single infrastructure component.

### Negative
- **Stale data window:** Between a product update and cache invalidation (seconds for event propagation + consumer processing), services may serve slightly stale product data. Acceptable for display purposes; not acceptable for business rule evaluation (mitigated by always fetching fresh data for validation-critical operations).
- **Cache invalidation complexity:** Event-driven invalidation adds Kafka consumers to every service that caches data. Each consumer must correctly handle all relevant events.
- **Redis operational overhead:** Redis is an additional infrastructure component to deploy, monitor, and manage. At the edge, it consumes 64 MB of the RAM budget.
- **L1 cache inconsistency across instances:** After a Kafka invalidation event, one service instance may have invalidated its L1 cache while another has not yet processed the event. For the brief window (milliseconds to seconds), different instances may serve different versions of the same data. Acceptable for reference data display; not for transactional operations.

### Neutral
- Caffeine provides hit rate metrics via Micrometer, enabling monitoring of cache effectiveness (`karyo_cache_hits_total`, `karyo_cache_misses_total`).
- Cache warming on service startup is not implemented initially. Cold caches populate naturally within minutes of service start as requests arrive.

## Alternatives Considered

### Alternative 1: Hazelcast (Distributed In-Process Cache)
- **Pros**: Eliminates Redis as a separate component, distributed cache with automatic discovery, near-cache (L1) built in, compute grid capabilities.
- **Cons**: JVM-based (adds 128-256 MB per node to services), complex cluster management (split-brain scenarios), too heavy for edge deployment, commercial features gated behind enterprise license.
- **Why rejected**: Hazelcast's embedded approach adds 128-256 MB RAM per service for the cache cluster, significantly increasing the edge footprint. Redis (64 MB at edge) + Caffeine (minimal overhead, in-process) is a lighter combination. Hazelcast's distributed computing capabilities are not needed for simple key-value caching.

### Alternative 2: Memcached
- **Pros**: Simple, fast, lightweight, proven at scale.
- **Cons**: No persistence (all data lost on restart), no data structures beyond key-value (cannot do sessions, locks, or rate limiting), no pub/sub for cache invalidation, no TTL per-key in older versions.
- **Why rejected**: Redis provides the same caching performance plus sessions, distributed locks, rate limiting, and sorted sets — four additional use cases that would otherwise require separate solutions. The marginal memory difference between Redis and Memcached is negligible.

### Alternative 3: No Caching
- **Pros**: Simplest architecture, no stale data risk, no cache invalidation complexity.
- **Cons**: Every product lookup and location lookup requires a cross-service REST call. At 10K orders/hour with 5-10 stock operations per order, product-service would receive 50K-100K requests/hour just from inventory-service. This creates unacceptable latency and load.
- **Why rejected**: The cross-service REST call volume without caching is unsustainable. Product-service and layout-service would need to scale far beyond their natural workload just to serve cache-equivalent queries from other services. The 200ms p95 API response time target cannot be met if every operation includes multiple synchronous REST calls to reference data services.

## Implementation Notes
- **Caffeine:** Use Quarkus Caffeine extension or manual `Caffeine.newBuilder()`. Configure `maximumSize` to prevent unbounded memory growth. Monitor with Micrometer.
- **Redis:** Use Quarkus Redis Client extension (`quarkus-redis-client`). JSON serialization with Jackson for cached objects.
- **Cache key naming:** `{service}:{entity}:{identifier}` (e.g., `product:item-data:SKU-001`, `layout:location:A-01-02-03`).
- **Cache metrics:** Export `karyo_cache_hits_total`, `karyo_cache_misses_total`, `karyo_cache_evictions_total` per cache name via Prometheus.
- **Graceful degradation:** If Redis is unavailable, services fall back to L1 (Caffeine) only. If L1 misses, direct REST call to source service. No hard dependency on Redis for operational functionality.

## Related Decisions
- [ADR-004: Database-per-Service Pattern](superseded/ADR-004-database-per-service.md)
- [ADR-006: Apache Kafka for Event Bus](superseded/ADR-006-kafka-event-bus.md)
- [ADR-008: REST for Synchronous, Events for Asynchronous](ADR-008-rest-sync-events-async.md)

## References
- Caffeine: https://github.com/ben-manes/caffeine
- Quarkus Redis Client: https://quarkus.io/guides/redis
- Cache-Aside Pattern: https://learn.microsoft.com/en-us/azure/architecture/patterns/cache-aside

## Revision History
- 2026-02-15: Initial version
