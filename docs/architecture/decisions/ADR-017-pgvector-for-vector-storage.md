# ADR-017: pgvector for Vector Storage

> **Implementation status (2026-09-08): parked, not implemented.** Acceptance below records
> a future design. No pgvector dependency, embedding table or vector runtime is installed by the
> current app. The optional copilot uses search tools, not this RAG architecture. [ADR index](README.md).

## Status
Accepted

## Context
Karyo WMS includes an AI Intelligence Service (artificial-intelligence-service) that provides natural language warehouse queries, intelligent slotting recommendations, demand forecasting, and document parsing capabilities. These AI features require storing and querying high-dimensional vector embeddings for Retrieval-Augmented Generation (RAG), semantic search over product catalogs, warehouse procedures, SOPs, and historical operational decisions.

The system needs a vector storage solution that:
- Supports approximate nearest neighbor (ANN) search over embedding vectors
- Can handle warehouse-scale document volumes (typically < 1M documents per tenant)
- Works in both cloud and edge deployment scenarios (< 2GB RAM edge footprint)
- Minimizes infrastructure complexity and operational overhead
- Integrates with the existing PostgreSQL-per-service database strategy (ADR-003)

## Decision
We will use the **pgvector** extension (v0.7+) within the artificial-intelligence-service's PostgreSQL database (`karyo_ai`) for all vector embedding storage and similarity search operations.

**Embedding configuration:**
- Model: OpenAI `text-embedding-3-large` (or equivalent open-source model for edge deployments)
- Vector dimensions: 1536
- Distance metric: cosine similarity (`<=>` operator)
- Index type: IVFFlat for approximate nearest neighbor search

**Index configuration:**
```sql
CREATE EXTENSION IF NOT EXISTS vector;

CREATE TABLE document_embeddings (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    source_type VARCHAR(50) NOT NULL,  -- 'product_catalog', 'sop', 'procedure', 'decision_log'
    source_id VARCHAR(255) NOT NULL,
    chunk_index INT NOT NULL DEFAULT 0,
    content TEXT NOT NULL,
    metadata JSONB,
    embedding vector(1536) NOT NULL,
    created TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    modified TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- IVFFlat index: lists = sqrt(num_rows) as starting point, tune based on data volume
CREATE INDEX idx_embeddings_ivfflat ON document_embeddings
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

-- Partition by tenant for multi-tenant isolation
CREATE INDEX idx_embeddings_tenant ON document_embeddings(tenant_id);
CREATE INDEX idx_embeddings_source ON document_embeddings(source_type, source_id);
```

**Query pattern:**
```sql
SELECT id, content, metadata, 1 - (embedding <=> $1) AS similarity
FROM document_embeddings
WHERE tenant_id = $2
  AND source_type = ANY($3)
ORDER BY embedding <=> $1
LIMIT 5;
```

**Edge deployment considerations:**
- Edge nodes running without cloud connectivity can use a smaller embedding model (e.g., `all-MiniLM-L6-v2` at 384 dimensions) with a separate column or table
- Pre-computed embeddings can be synced from cloud to edge via Kafka event replication
- IVFFlat index memory footprint is proportional to data size, manageable at edge scale

## Consequences

### Positive
- No additional infrastructure component to deploy, monitor, or scale — pgvector runs as a PostgreSQL extension within the existing database
- Consistent backup, recovery, and HA strategy with the rest of the PostgreSQL databases
- Full ACID transaction support — vector operations participate in the same transactions as related metadata updates
- PostgreSQL's mature query planner can combine vector similarity search with standard SQL filters (tenant_id, source_type, date ranges) in a single query
- Works on edge deployments without requiring a separate vector database service
- Reduced operational cost — no additional SaaS subscription or infrastructure component
- pgvector is actively maintained, widely adopted, and has strong community support

### Negative
- Performance ceiling: pgvector with IVFFlat is slower than purpose-built vector databases (Pinecone, Qdrant) at very large scale (> 10M vectors)
- IVFFlat index requires periodic rebuilding as data distribution changes significantly
- Limited to approximate nearest neighbor search — no hybrid search (keyword + vector) built-in without additional extensions (pg_trgm can supplement)
- PostgreSQL resource contention: heavy vector queries on the same database instance as OLTP operations could impact performance
- No built-in vector compression (quantization) — full 1536-dimension float32 vectors consume ~6KB each

### Neutral
- HNSW index (available in pgvector 0.5+) can be used as an alternative to IVFFlat for better recall at the cost of higher memory usage; decision on index type can be revisited based on production performance data
- If vector search volumes grow beyond PostgreSQL's capacity, migration to a dedicated vector store is possible without changing the application-level abstraction (LangChain4j's EmbeddingStore interface)

## Alternatives Considered

### Alternative 1: Pinecone
- **Pros**: Purpose-built for vector search, fully managed SaaS, excellent query performance at scale, built-in hybrid search, automatic index optimization
- **Cons**: SaaS-only (no self-hosted option), per-vector pricing becomes expensive at scale, cannot run on edge deployments, adds external dependency for AI features, data residency concerns for on-premise customers
- **Why rejected**: Incompatible with edge deployment requirement and on-premise deployment model. SaaS cost adds up for multi-tenant deployments. Vendor lock-in contradicts open-source commitment.

### Alternative 2: Weaviate
- **Pros**: Open-source, supports hybrid search (BM25 + vector), GraphQL API, multi-modal support, built-in vectorization modules
- **Cons**: Requires separate infrastructure (dedicated container/service), additional operational overhead, heavier resource footprint than pgvector, different backup/recovery procedures from PostgreSQL
- **Why rejected**: Additional infrastructure complexity not justified for warehouse-scale vector volumes (< 1M documents). Would increase edge deployment footprint beyond the 2GB RAM target.

### Alternative 3: Qdrant
- **Pros**: Open-source, Rust-based (high performance), rich filtering capabilities, gRPC API, quantization support for memory efficiency
- **Cons**: Separate service to deploy and manage, different operational model from PostgreSQL, smaller community than pgvector, would need separate HA and backup strategy
- **Why rejected**: Same infrastructure complexity concerns as Weaviate. Performance advantages over pgvector not needed at our scale.

### Alternative 4: Milvus
- **Pros**: Open-source, designed for billion-scale vector search, distributed architecture, GPU acceleration support
- **Cons**: Heavy infrastructure requirements (etcd, MinIO, Pulsar), complex deployment, overkill for warehouse-scale queries, very large resource footprint
- **Why rejected**: Massively overengineered for our use case. Minimum deployment requires multiple services (etcd, MinIO, message queue) which contradicts our infrastructure simplicity goals, especially for edge deployments.

## Implementation Notes
- Start with IVFFlat index; benchmark with production-like data and switch to HNSW if recall is insufficient at the required query latency
- Set `ivfflat.probes` to 10-20 at query time for a balance between recall and speed; tune based on benchmarks
- Implement a background job to periodically rebuild IVFFlat indexes as document volume grows (e.g., weekly or when row count changes by > 20%)
- Use connection pooling (Agroal) to prevent vector queries from saturating the database connection pool — consider a separate pool for AI queries
- For edge deployments, use a smaller embedding model and reduce vector dimensions to 384 to minimize storage and memory footprint
- LangChain4j's `PgVectorEmbeddingStore` provides the application-level abstraction, making future migration to a different vector store possible without changing business logic

## Related Decisions
- [ADR-004](superseded/ADR-004-database-per-service.md): Database-per-Service Pattern — pgvector extends this pattern to the artificial-intelligence-service
- [ADR-018](ADR-018-rag-architecture-for-ai-features.md): RAG Architecture — defines how pgvector is used in the retrieval pipeline
- [ADR-005](ADR-005-postgresql-database.md): PostgreSQL as Primary Database — pgvector keeps vector storage inside that same database rather than adding a dedicated vector store

## References
- [pgvector GitHub repository](https://github.com/pgvector/pgvector)
- [pgvector: Open-Source Extension for Vector Similarity Search](https://www.postgresql.org/about/news/pgvector-060-released-2610/)
- [LangChain4j PgVector Integration](https://docs.langchain4j.dev/integrations/embedding-stores/pgvector)
- [IVFFlat vs HNSW Performance Benchmarks](https://ann-benchmarks.com/)

## Revision History
- 2026-02-15: Initial version
