# ADR-018: RAG Architecture for AI Features

> **Implementation status (2026-09-08): parked, not implemented.** The RAG design is retained,
> not a capability supplied by the current copilot. Document AI and embedding/search infrastructure
> do not become available by configuring an LLM provider. [ADR index](README.md).

## Status
Accepted

## Context
Karyo WMS's AI Intelligence Service needs to answer domain-specific warehouse queries, provide operational recommendations, and assist with decision-making. The AI must understand warehouse-specific context including product catalogs, standard operating procedures (SOPs), warehouse layout configurations, historical operational decisions, and regulatory requirements.

The core question is how to provide domain-specific knowledge to the LLM:
1. **Fine-tuning**: Train a custom model on warehouse data
2. **RAG (Retrieval-Augmented Generation)**: Retrieve relevant context at query time and inject it into the prompt
3. **Pure prompt engineering**: Rely solely on system prompts with static context

The AI service must support use cases such as:
- "Show me all expired stock in zone A" (natural language to structured query)
- "What is the SOP for handling damaged goods during receiving?" (procedure lookup)
- "Suggest optimal slotting for SKU-12345 based on movement history" (analytical recommendation)
- "Why was this stock moved to location B-03-02?" (historical decision explanation)

## Decision
We will implement a **Retrieval-Augmented Generation (RAG)** architecture as the primary method for providing domain context to the AI service, using pgvector (ADR-017) as the vector store and LangChain4j as the orchestration framework.

**RAG Pipeline Architecture:**

```
User Query
    │
    ▼
┌──────────────────┐
│  Query Processor  │  ← Classify intent, extract entities (item numbers, locations)
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Embedding Model   │  ← text-embedding-3-large (1536 dims)
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ pgvector Search   │  ← Cosine similarity, top-k retrieval
│ (karyo_ai DB)     │     Filtered by: tenant_id, source_type, recency
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Context Assembly  │  ← System prompt + retrieved chunks + user query
│ & Reranking       │     Optional: cross-encoder reranking for precision
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│  LLM (Claude)     │  ← Generate response with citations
└────────┬─────────┘
         │
         ▼
┌──────────────────┐
│ Response Handler  │  ← Parse tool calls, validate citations, format output
└──────────────────┘
```

**Chunking Strategy:**
- Chunk size: 512 tokens with 50-token overlap between consecutive chunks
- Chunking method: recursive text splitting respecting document structure (headings, paragraphs, code blocks)
- Metadata preserved per chunk: source document ID, section heading, document type, tenant ID, last modified timestamp
- Special handling for structured data: product catalog entries are chunked per product, SOPs per procedure step

**Retrieval Configuration:**
- Top-k: 5 chunks retrieved per query (configurable per use case)
- Distance metric: cosine similarity via pgvector `<=>` operator
- Minimum similarity threshold: 0.7 (chunks below this threshold are excluded)
- Pre-filtering: tenant_id (mandatory), source_type (optional, based on query classification)
- Post-retrieval: optional cross-encoder reranking when precision is critical (e.g., regulatory queries)

**Document Sources and Ingestion:**

| Source Type | Ingestion Trigger | Update Frequency | Example Content |
|------------|------------------|-----------------|----------------|
| Product Catalog | product-service Kafka event | Real-time (on product change) | Item descriptions, handling instructions, hazmat info |
| SOPs | Manual upload via admin UI | On change | Receiving procedures, picking guidelines, safety protocols |
| Warehouse Procedures | Manual upload or integration-hub | On change | Custom workflows, exception handling, escalation paths |
| Historical Decisions | Automated from InventoryJournal events | Real-time | Stock movements, location assignments, adjustment reasons |
| Warehouse Layout | warehouse-layout-service Kafka event | On layout change | Zone descriptions, area purposes, location naming conventions |
| Regulatory Docs | Manual upload | On regulatory change | FDA 21 CFR Part 11, GDPR data handling, hazmat regulations |

**Prompt Template Structure:**
```
System: You are Karyo AI, a warehouse management assistant for {warehouse_name}.
You help warehouse operators and managers with queries about inventory,
operations, and procedures. Always cite your sources.

Context (retrieved from knowledge base):
---
[Chunk 1: {source_type} - {document_title} - {section}]
{content}

[Chunk 2: ...]
{content}
...
---

Tools available:
- getStockLevel(itemNumber): Query current stock levels
- findStorageLocation(unitLoadType, itemNumber): Find optimal storage location
- getOrderStatus(orderNumber): Check order fulfillment status
- getLocationInventory(locationName): List all stock at a location

User: {user_query}
```

## Consequences

### Positive
- No model training required — domain knowledge is injected at query time from the vector store, avoiding the cost and complexity of fine-tuning
- Always current data — when a product catalog or SOP changes, re-embedding the updated document immediately makes the new information available to the AI
- Explainable responses — every AI response can cite the specific source chunks it used, enabling operators and auditors to verify the information
- Multi-tenant by design — vector search is filtered by tenant_id, ensuring each tenant's AI only accesses their own documents
- Incremental improvement — adding new document types or expanding the knowledge base requires only ingestion pipeline changes, no model retraining
- Cost-effective — embedding API costs are minimal compared to fine-tuning costs, and cached embeddings reduce repeated embedding calls
- Works with any LLM — the RAG architecture is model-agnostic; switching from Claude to another LLM requires no changes to the retrieval pipeline

### Negative
- Retrieval quality ceiling — RAG can only surface information that exists in the indexed documents; it cannot generalize from patterns the way fine-tuning can
- Latency overhead — the retrieval step adds 50-200ms to each AI query (embedding + pgvector search + context assembly) on top of LLM inference time
- Chunk boundary issues — relevant information may span chunk boundaries; the 50-token overlap mitigates but does not fully solve this
- Context window consumption — injecting 5 chunks of 512 tokens each consumes ~2,560 tokens of the LLM's context window, leaving less room for conversation history
- Embedding drift — if the embedding model is updated, all stored embeddings must be re-computed to maintain consistency

### Neutral
- The 512-token chunk size and k=5 retrieval parameters are starting points; they should be tuned based on production query quality metrics
- Cross-encoder reranking adds latency but improves precision; it should be enabled selectively for high-stakes queries (regulatory, compliance)
- LangChain4j provides the orchestration abstraction, but the pipeline architecture is framework-independent

## Alternatives Considered

### Alternative 1: Fine-tuning a Custom Model
- **Pros**: Can learn warehouse-specific patterns and terminology deeply, potentially higher quality for common query types, no retrieval latency overhead, smaller context window usage
- **Cons**: Expensive to train and maintain (GPU compute, data preparation), model becomes stale as warehouse data changes (requires periodic retraining), no inherent citability (model "knows" things but cannot point to sources), each tenant would need separate fine-tuning or multi-tenant training data management, regulatory risk (model may hallucinate compliance information with high confidence)
- **Why rejected**: The cost of maintaining a fine-tuned model across multiple tenants with constantly changing warehouse data is prohibitive. The inability to cite sources is a dealbreaker for warehouse operations where traceability and auditability are critical (especially for pharma/FDA-regulated warehouses).

### Alternative 2: Pure Prompt Engineering (Static Context)
- **Pros**: Simplest implementation (no vector store, no embedding pipeline), lowest latency, predictable behavior
- **Cons**: Context window is finite — cannot fit entire product catalogs, all SOPs, and historical data into a single prompt. Static context becomes stale without manual updates. Not scalable across tenants with different configurations and procedures.
- **Why rejected**: Warehouse knowledge bases easily exceed LLM context window limits. A single warehouse may have thousands of products, dozens of SOPs, and millions of historical decisions. Static prompts cannot scale to this volume.

### Alternative 3: Hybrid RAG + Fine-tuning
- **Pros**: Fine-tuned model understands warehouse domain terminology natively, RAG provides current factual data, best of both approaches
- **Cons**: Highest complexity and cost, requires maintaining both the fine-tuning pipeline and the RAG pipeline, difficult to attribute responses to fine-tuned knowledge vs. retrieved context, premature optimization for the current project phase
- **Why rejected**: Premature optimization. RAG alone is sufficient for the initial AI features. If production usage reveals quality gaps that RAG cannot address, fine-tuning can be added as a layer on top of the existing RAG architecture without redesigning the system.

## Implementation Notes
- Use LangChain4j's `EmbeddingStoreContentRetriever` with `PgVectorEmbeddingStore` for the retrieval pipeline
- Implement a document ingestion service within artificial-intelligence-service that subscribes to Kafka topics (`karyo.product.item-data.changed`, `karyo.layout.storage-location.changed`) for real-time re-embedding
- Build an admin UI for uploading and managing SOPs and procedure documents, with preview of chunking results
- Implement embedding caching: hash the input text and skip re-embedding if the hash matches an existing embedding
- Monitor retrieval quality metrics: track user feedback (thumbs up/down), retrieval latency, similarity scores of returned chunks, and query classification accuracy
- For edge deployments without cloud LLM access, support a fallback to local open-source models (e.g., Llama) with reduced capability, using the same RAG pipeline with locally stored embeddings
- Rate-limit AI queries per tenant to prevent cost overruns on LLM API calls

## Related Decisions
- [ADR-017](ADR-017-pgvector-for-vector-storage.md): pgvector for Vector Storage — provides the vector store used in the retrieval step
- [ADR-005](ADR-005-postgresql-database.md): PostgreSQL as Primary Database — the foundational decision that lets pgvector serve this architecture
- [ADR-006](superseded/ADR-006-kafka-event-bus.md): Apache Kafka for Event Bus — enables real-time document re-embedding on data changes
- [ADR-020](ADR-020-multi-tenancy-strategy.md): Multi-Tenancy Strategy — tenant isolation applies to vector search filtering

## References
- [LangChain4j RAG Documentation](https://docs.langchain4j.dev/tutorials/rag)
- [Retrieval-Augmented Generation for Knowledge-Intensive NLP Tasks (Lewis et al., 2020)](https://arxiv.org/abs/2005.11401)
- [Anthropic Claude Tool Use Documentation](https://docs.anthropic.com/claude/docs/tool-use)
- [pgvector Nearest Neighbor Search](https://github.com/pgvector/pgvector#querying)

## Revision History
- 2026-02-15: Initial version
