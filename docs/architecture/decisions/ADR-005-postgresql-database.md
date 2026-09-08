# ADR-005: PostgreSQL as Primary Database

> **Implementation context (2026-09-08):** PostgreSQL remains the database choice; one `karyo`
> application schema replaces per-service databases. The pgvector, RLS and partitioning examples
> below are not the installed migration chain. Use `application.yaml` and versioned migrations
> for actual schema/configuration, and the [ADR index](README.md) for current disposition.

## Status
Accepted

## Context
Karyo WMS requires a database that supports:

1. **ACID transactions:** Inventory operations (stock reservation, transfer, adjustment) demand transactional integrity. A picking operation that reserves stock and creates a pick line must be atomic — partial execution could result in double-picks or phantom inventory.
2. **High write throughput:** The InventoryJournal is an immutable audit trail that records every stock change. At 10K orders/hour with multiple stock operations per order, the journal table can grow to millions of rows per month.
3. **Complex queries:** The PickingStockFinder (13-pass algorithm) and LocationFinderBean (19-filter query) require expressive SQL with window functions, CTEs, and efficient indexing.
4. **Vector search (AI):** The artificial-intelligence-service needs vector similarity search for RAG embeddings without introducing a separate vector database.
5. **JSON flexibility:** OrderStrategy and other configuration entities benefit from JSONB extension properties for customer-specific fields.
6. **Edge deployment:** The database must run within the < 2GB RAM constraint at edge sites, allocating approximately 256 MB to PostgreSQL.
7. **Multi-tenancy:** Row-level security (RLS) is needed as defense-in-depth for tenant data isolation.
8. **Proven reliability:** 24/7 warehouse operations cannot tolerate database instability or data loss.

myWMS was originally deployed with MySQL, PostgreSQL, and HSQLDB options. The Karyo migration standardizes on a single database technology.

## Decision
We will use **PostgreSQL 16+** as the primary (and only) database technology for all Karyo WMS services.

### PostgreSQL Features Used

| Feature | Purpose | Service |
|---------|---------|---------|
| ACID transactions | Inventory operations, order processing | All services |
| JSONB | Extension properties on OrderStrategy, IntegrationChannel config | order-service, integration-hub |
| Table partitioning (RANGE) | InventoryJournal by month | inventory-service |
| Row-Level Security (RLS) | Defense-in-depth tenant isolation | All services |
| pgvector extension | AI embeddings, similarity search | artificial-intelligence-service |
| Window functions | OrderStateCalculator aggregation, stock ranking | order-service, inventory-service |
| CTEs (Common Table Expressions) | Complex stock selection queries, location finding | inventory-service, layout-service |
| Partial indexes | Index only active/unlocked stock for picking | inventory-service |
| Connection pooling (Agroal) | Efficient connection management | All services (Quarkus built-in) |
| Materialized views | Reporting aggregations | reporting-service |
| LISTEN/NOTIFY | Optional real-time cache invalidation | inventory-service (future) |

### InventoryJournal Partitioning

```sql
CREATE TABLE inventory_journals (
    id BIGSERIAL,
    created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    client_id BIGINT NOT NULL,
    record_type INT NOT NULL,
    amount NUMERIC(17,4),
    stock_unit_amount NUMERIC(17,4),
    from_unit_load VARCHAR(255),
    to_unit_load VARCHAR(255),
    from_storage_location VARCHAR(255),
    to_storage_location VARCHAR(255),
    activity_code VARCHAR(255),
    product_number VARCHAR(255),
    product_name VARCHAR(255),
    lot_number VARCHAR(255),
    PRIMARY KEY (id, created)
) PARTITION BY RANGE (created);

-- Monthly partitions managed by pg_partman or scheduled job
CREATE TABLE inventory_journals_2026_01 PARTITION OF inventory_journals
    FOR VALUES FROM ('2026-01-01') TO ('2026-02-01');
```

### Key Indexes

```sql
-- Stock selection (PickingStockFinder hot path)
CREATE INDEX idx_stock_units_picking ON stock_units(item_data_id, state, lock_type)
    WHERE state = 300 AND lock_type = 0;  -- Partial index: only ON_STOCK + UNLOCKED

-- Location finding (LocationFinderBean hot path)
CREATE INDEX idx_locations_available ON storage_locations(area_id, zone_id, lock_type, allocation)
    WHERE lock_type = 0;  -- Partial index: only unlocked locations

-- FIFO ordering for stock selection
CREATE INDEX idx_stock_units_fifo ON stock_units(strategy_date, amount, created, id);
```

### pgvector for AI Embeddings

```sql
-- In karyo_ai schema
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE embedding_documents (
    id BIGSERIAL PRIMARY KEY,
    source_type VARCHAR(50) NOT NULL,
    source_id VARCHAR(100),
    content TEXT NOT NULL,
    embedding vector(1536),
    metadata JSONB,
    created TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_embedding_cosine ON embedding_documents
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);
```

## Consequences

### Positive
- **ACID guarantees:** Critical for WMS operations where stock consistency is paramount. A failed stock reservation rolls back cleanly, preventing phantom inventory.
- **SQL expressiveness:** The 13-pass stock selection algorithm and 19-filter location finder can be implemented as efficient JPQL/native SQL queries using PostgreSQL's window functions, CTEs, and partial indexes.
- **pgvector eliminates separate vector DB:** AI embeddings stored alongside relational data, reducing infrastructure complexity. One PostgreSQL instance per AI service instead of PostgreSQL + Pinecone/Weaviate.
- **Table partitioning:** InventoryJournal partitioned by month enables efficient querying of recent audit data while keeping historical data accessible. Partition drops replace expensive DELETEs for data retention.
- **JSONB flexibility:** OrderStrategy extension properties allow customer-specific configuration without schema changes. Integration channel configs stored as JSONB avoid rigid column structures.
- **RLS for multi-tenancy:** Defense-in-depth tenant isolation at the database level, in addition to application-level filtering. A bug in application code cannot leak data across tenants if RLS policies are enforced.
- **Operational simplicity:** One database technology across all 10 services. Operations team manages one type of backup, monitoring, failover, and tuning.
- **Edge compatibility:** PostgreSQL runs efficiently at 256 MB RAM allocation. Proven in embedded/edge scenarios.
- **Proven at scale:** PostgreSQL handles the target throughput (10K orders/hour) comfortably with proper indexing and connection pooling.

### Negative
- **pgvector scalability limits:** For very large embedding collections (100M+ documents), a dedicated vector database (Pinecone, Weaviate) would outperform pgvector. However, Karyo WMS embedding volumes (SOPs, product catalogs, decision logs) are expected to be in the thousands to low millions range, well within pgvector capabilities.
- **Single technology risk:** If a use case emerges that PostgreSQL handles poorly (e.g., high-frequency time-series from IoT sensors), we would need to introduce a second database technology. Current requirements are fully covered.
- **Connection management:** With 10 services each maintaining connection pools (min 2, max 20), a shared PostgreSQL instance could face connection pressure. Mitigated by Agroal connection pooling and PgBouncer for production deployments.
- **Replication complexity:** Cross-region or edge-to-cloud replication requires either logical replication (complex setup) or application-level sync via Kafka events (chosen approach).

### Neutral
- PostgreSQL 16+ is available in all major cloud managed database services (RDS, Cloud SQL, Azure Database for PostgreSQL), ensuring no vendor lock-in.
- The Quarkus Hibernate ORM Panache extension provides Kotlin-friendly abstractions over JDBC, but native SQL queries are available when needed for complex algorithms.

## Alternatives Considered

### Alternative 1: MySQL 8.x
- **Pros**: Widely used, large community, good cloud managed service support, slightly simpler administration for basic use cases.
- **Cons**: No pgvector equivalent (requires separate vector DB for AI), weaker window function support (critical for stock selection algorithm), no JSONB (JSON type exists but less performant for queries), no row-level security (RLS), no native table partitioning for time-series data (partitioning exists but less mature), less expressive SQL for complex CTEs.
- **Why rejected**: The lack of pgvector would require a separate vector database for the AI service, increasing infrastructure complexity. The weaker SQL expressiveness for window functions and CTEs would make the PickingStockFinder and LocationFinderBean implementations more complex. RLS for multi-tenant defense-in-depth is not available.

### Alternative 2: MongoDB
- **Pros**: Flexible schema (good for evolving entities), horizontal scaling (sharding), JSON-native document model, Atlas vector search for AI embeddings.
- **Cons**: No ACID transactions across documents (multi-document transactions exist but with limitations), the WMS domain is inherently relational (stock units belong to unit loads, which are at storage locations — graph of relationships), less efficient for the complex join-heavy queries in stock selection and location finding, no mature Quarkus Panache equivalent, Flyway does not support MongoDB.
- **Why rejected**: The WMS domain is fundamentally relational. Stock selection requires joining stock units with unit loads, locations, and client data with complex ordering and filtering. MongoDB's document model would require either data denormalization (creating consistency challenges) or multiple queries (increasing latency). The ACID requirement for inventory operations is best served by a relational database.

### Alternative 3: CockroachDB
- **Pros**: Distributed SQL, horizontal scaling, strong consistency across distributed nodes, PostgreSQL wire compatibility.
- **Cons**: Higher memory footprint (minimum 2GB per node — exceeds entire edge RAM budget), more complex operations (distributed consensus overhead), not suitable for edge/K3s deployment, higher latency for single-node queries due to distributed transaction protocol, overkill for most WMS deployments.
- **Why rejected**: CockroachDB's minimum resource requirements (2GB+ per node) make it incompatible with the edge deployment constraint. Single-warehouse deployments do not need distributed SQL — PostgreSQL's native replication handles the read scaling needs. If multi-region active-active is needed in the future, CockroachDB could be evaluated for cloud-only reporting/analytics, but it cannot replace PostgreSQL at the edge.

## Implementation Notes
- **Edge:** Single PostgreSQL 16 instance, 256 MB RAM, with separate schemas per service. Use `pg_partman` extension for automated partition management of InventoryJournal.
- **Cloud (small):** Shared managed PostgreSQL instance (e.g., RDS db.t3.medium) with separate schemas.
- **Cloud (large):** Separate managed PostgreSQL instances per service or service group. Inventory-service gets a dedicated instance due to high write volume.
- **Connection pooling:** Quarkus Agroal built-in. For production with many service replicas, add PgBouncer as a connection pooler in front of PostgreSQL.
- **Backups:** Automated daily backups with point-in-time recovery (PITR). Cloud: managed service handles this. Edge: pg_basebackup + WAL archiving.
- **Monitoring:** `pg_stat_statements` for query performance, Prometheus `postgres_exporter` for metrics.
- **Flyway integration:** Quarkus Flyway extension auto-runs migrations on startup. In K8s, migrations run as init containers before the service pod starts (see [ADR-014](ADR-014-flyway-migrations.md)).

## Related Decisions
- [ADR-004: Database-per-Service Pattern](superseded/ADR-004-database-per-service.md)
- [ADR-006: Apache Kafka for Event Bus](superseded/ADR-006-kafka-event-bus.md)
- [ADR-012: CQRS for Reporting Service](ADR-012-cqrs-reporting.md)
- [ADR-014: Flyway for Database Migrations](ADR-014-flyway-migrations.md)
- [ADR-015: LangChain4j for LLM Orchestration](ADR-015-langchain4j.md)

## References
- PostgreSQL 16: https://www.postgresql.org/docs/16/
- pgvector: https://github.com/pgvector/pgvector
- pg_partman: https://github.com/pgpartman/pg_partman
- PostgreSQL Row-Level Security: https://www.postgresql.org/docs/16/ddl-rowsecurity.html
- Quarkus Datasource guide: https://quarkus.io/guides/datasource

## Revision History
- 2026-02-15: Initial version
