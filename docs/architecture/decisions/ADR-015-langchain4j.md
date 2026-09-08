# ADR-015: LangChain4j for LLM Orchestration

> **Implementation context (2026-09-08):** LangChain4j powers the optional copilot in
> `services/ai-service`. Tool queries and confirmed action proposals exist; RAG and document-AI
> examples below remain future intent. Provider extensions are fixed during Quarkus augmentation.
> Current pins are in the version catalog. [ADR index](README.md).

## Status
Accepted

## Context
Karyo WMS's artificial-intelligence-service (see [ADR-001](superseded/ADR-001-microservices-architecture.md)) provides AI-enhanced capabilities:

1. **Natural language warehouse queries:** "Show me expired stock in zone A" or "Which products have the lowest turn rate?"
2. **Tool calling:** The LLM needs to call Karyo service APIs to fetch real-time data (current stock levels, order status, location utilization) and present results in natural language.
3. **RAG (Retrieval-Augmented Generation):** Embedding warehouse SOPs, product catalogs, and historical decisions in pgvector, then retrieving relevant context for query augmentation.
4. **Structured output parsing:** Predictions (demand forecasting) and recommendations (slotting) must be parsed into typed Kotlin objects.
5. **Conversation memory:** Multi-turn queries ("Show me stock in zone A" → "Now filter by items expiring this month") require conversation context management.

The artificial-intelligence-service runs on Quarkus + Kotlin (same stack as all other services). The LLM orchestration framework must:
- Be Java/Kotlin-native (JVM)
- Integrate with Quarkus CDI
- Support tool calling for real-time data access
- Support RAG with pgvector
- Support multiple LLM providers (Claude primary, local Ollama fallback for edge)
- Handle token budget management

## Decision
We will use **LangChain4j** as the LLM orchestration framework for the artificial-intelligence-service, via the **Quarkus LangChain4j extension**.

### Architecture

```
┌──────────────────────────────────────────────────────────┐
│                  artificial-intelligence-service                   │
│                                                            │
│  ┌───────────────┐    ┌──────────────────────────────┐   │
│  │  REST API     │    │     LangChain4j              │   │
│  │               │───►│                              │   │
│  │ POST /ai/query│    │  ┌───────────┐  ┌─────────┐ │   │
│  │ GET /ai/...   │    │  │ AI Service│  │ Memory  │ │   │
│  │               │    │  │ (Tools)   │  │ Manager │ │   │
│  └───────────────┘    │  └─────┬─────┘  └─────────┘ │   │
│                       │        │                      │   │
│                       │  ┌─────▼──────┐  ┌─────────┐ │   │
│                       │  │   Tool     │  │  RAG    │ │   │
│                       │  │  Executor  │  │ Pipeline│ │   │
│                       │  └─────┬──────┘  └────┬────┘ │   │
│                       └────────┼──────────────┼──────┘   │
│                                │              │          │
│                           REST calls     pgvector        │
│                                │              │          │
└────────────────────────────────┼──────────────┼──────────┘
                                 │              │
                    ┌────────────▼──┐   ┌───────▼────────┐
                    │ Karyo Services │   │   PostgreSQL   │
                    │ (inventory,    │   │   (pgvector    │
                    │  order, layout)│   │   embeddings)  │
                    └───────────────┘   └────────────────┘
```

### Tool Calling Pattern

```kotlin
@ApplicationScoped
class WarehouseQueryTools(
    @RestClient private val inventoryClient: InventoryServiceClient,
    @RestClient private val orderClient: OrderServiceClient,
    @RestClient private val layoutClient: LayoutServiceClient,
) {
    @Tool("Get current stock level for a product by SKU number")
    fun getStockLevel(
        @P("Product SKU number") itemNumber: String,
    ): String {
        val stock = inventoryClient.getStockByItem(itemNumber)
        return "Product $itemNumber: ${stock.totalAmount} available across ${stock.locationCount} locations"
    }

    @Tool("Find all stock expiring within a given number of days")
    fun getExpiringStock(
        @P("Number of days until expiry") days: Int,
        @P("Optional zone name filter") zone: String?,
    ): String {
        val expiringStock = inventoryClient.getExpiringStock(days, zone)
        return expiringStock.joinToString("\n") {
            "${it.productNumber}: ${it.amount} units at ${it.locationName}, expires ${it.bestBefore}"
        }
    }

    @Tool("Get order fulfillment status for a delivery order")
    fun getOrderStatus(
        @P("Delivery order number") orderNumber: String,
    ): String {
        val order = orderClient.getByNumber(orderNumber)
        return "Order $orderNumber: state=${order.state}, ${order.pickedLines}/${order.totalLines} lines picked"
    }

    @Tool("Get warehouse location utilization by zone")
    fun getLocationUtilization(
        @P("Zone name") zoneName: String,
    ): String {
        val utilization = layoutClient.getUtilizationByZone(zoneName)
        return "Zone $zoneName: ${utilization.usedLocations}/${utilization.totalLocations} locations used, avg allocation ${utilization.avgAllocation}%"
    }
}
```

### RAG Pipeline

```kotlin
@ApplicationScoped
class WarehouseRagService(
    private val embeddingModel: EmbeddingModel,
    private val embeddingStore: PgVectorEmbeddingStore,
) {
    fun buildRetrievalAugmentor(): RetrievalAugmentor {
        val contentRetriever = EmbeddingStoreContentRetriever.builder()
            .embeddingStore(embeddingStore)
            .embeddingModel(embeddingModel)
            .maxResults(5)
            .minScore(0.7)
            .build()

        return DefaultRetrievalAugmentor.builder()
            .contentRetriever(contentRetriever)
            .build()
    }
}
```

### AI Service Interface

```kotlin
@RegisterAiService
interface WarehouseAiAssistant {
    @SystemMessage("""
        You are a warehouse management assistant for Karyo WMS.
        You have access to tools that query real-time warehouse data.
        Always use tools to get current data - never guess stock levels or order statuses.
        Respond concisely and include specific numbers from tool results.
        When asked about expired or expiring stock, always check the current date.
    """)
    fun chat(@UserMessage query: String): String
}
```

### LLM Provider Configuration

```properties
# Primary: Claude API
quarkus.langchain4j.anthropic.api-key=${ANTHROPIC_API_KEY}
quarkus.langchain4j.anthropic.chat-model.model-name=claude-sonnet-4-20250514
quarkus.langchain4j.anthropic.chat-model.max-tokens=4096

# Fallback (edge/offline): Ollama
quarkus.langchain4j.ollama.base-url=http://localhost:11434
quarkus.langchain4j.ollama.chat-model.model-name=llama3
```

## Consequences

### Positive
- **Java/Kotlin-native:** LangChain4j runs on the JVM, integrating seamlessly with the Quarkus + Kotlin stack. No Python runtime, no sidecar, no inter-language bridge.
- **Quarkus extension:** The `quarkus-langchain4j` extension provides CDI integration (`@RegisterAiService`, `@Tool`), Dev Services for local testing, and configuration via `application.properties`.
- **Tool calling:** `@Tool` annotations make it straightforward to expose Karyo service APIs to the LLM. The LLM can query real-time stock levels, order statuses, and location data by calling annotated methods.
- **Multi-provider support:** LangChain4j supports Claude (primary), OpenAI, Ollama (edge fallback), and other providers through a unified API. Switching providers requires only configuration changes, not code changes.
- **RAG with pgvector:** LangChain4j's `PgVectorEmbeddingStore` integrates directly with PostgreSQL pgvector, avoiding a separate vector database (aligned with [ADR-005](ADR-005-postgresql-database.md)).
- **Structured output:** LangChain4j supports parsing LLM responses into typed Kotlin objects (predictions, recommendations), reducing manual JSON parsing.
- **Active development:** LangChain4j has an active community and regular releases, with Quarkus integration maintained by the Quarkus team.

### Negative
- **Newer framework:** LangChain4j is less mature than the Python LangChain ecosystem. Some advanced patterns (complex agent chains, multi-step reasoning) may require custom implementation.
- **Tool calling limitations:** Tool descriptions must be concise and clear for the LLM to use them correctly. Poorly described tools lead to incorrect or missed tool calls.
- **Token budget management:** LangChain4j provides basic token counting, but complex budget management (limiting cost per query, per user, per tenant) requires custom implementation on top.
- **Testing complexity:** Unit testing AI interactions requires mocking the LLM provider. Quarkus Dev Services provides a test model, but behavior differs from production models.

### Neutral
- LangChain4j's API is similar to the Python LangChain API, making knowledge transfer possible for developers familiar with the Python ecosystem.
- The `@RegisterAiService` pattern in Quarkus creates a CDI-managed interface, making AI services injectable like any other Quarkus service.

## Alternatives Considered

### Alternative 1: LangChain (Python)
- **Pros**: Largest LLM framework ecosystem, most community examples and tutorials, broadest LLM provider support, most mature agent patterns.
- **Cons**: Requires Python runtime (separate service or sidecar), inter-language communication overhead (REST or gRPC bridge between Kotlin services and Python AI service), different build/test/deploy pipeline, team needs Python expertise in addition to Kotlin, does not integrate with Quarkus CDI or Kotlin coroutines.
- **Why rejected**: Introducing a Python runtime for a single service adds operational complexity (separate container, separate dependencies, separate CI pipeline) and inter-language overhead. LangChain4j provides sufficient capabilities for the Karyo WMS AI use cases (NL queries, tool calling, RAG) while staying within the JVM ecosystem.

### Alternative 2: Semantic Kernel (Microsoft)
- **Pros**: Microsoft-backed, good Azure OpenAI integration, plugin-based architecture, strong .NET and Java support.
- **Cons**: Java SDK is less mature than the .NET version, smaller community for the Java SDK, tighter coupling to Azure/OpenAI ecosystem (Claude support requires community contributions), no Quarkus extension (requires manual integration).
- **Why rejected**: Semantic Kernel's Java SDK is less mature than LangChain4j, and its community gravity is centered around Microsoft/.NET. The lack of a Quarkus extension means more manual integration work. LangChain4j's Claude-first support (via the `langchain4j-anthropic` module) aligns with Karyo's primary LLM choice.

### Alternative 3: Direct API Calls (No Framework)
- **Pros**: No framework dependency, full control over API calls, minimal abstraction overhead, simplest possible implementation for basic use cases.
- **Cons**: Must implement tool calling protocol manually (parsing LLM tool requests, executing tools, sending results back), must implement RAG pipeline manually (embedding, similarity search, prompt augmentation), must implement conversation memory manually, must handle token counting and budget management manually, no multi-provider abstraction (switching from Claude to Ollama requires code changes).
- **Why rejected**: The value of LangChain4j is precisely in these abstractions — tool calling, RAG, memory, multi-provider support. Implementing them manually would duplicate significant framework functionality and delay AI feature delivery. The overhead of the framework dependency is minimal compared to the development time saved.

## Implementation Notes
- **Quarkus extensions to add:** `quarkus-langchain4j-anthropic` (Claude), `quarkus-langchain4j-ollama` (edge fallback), `quarkus-langchain4j-pgvector` (RAG embeddings).
- **Token budget:** Implement a `TokenBudgetInterceptor` that tracks tokens per tenant per month. Alert when approaching budget limits. Configurable per tenant.
- **Caching:** Cache frequent query patterns (e.g., "total inventory value" can be cached for 5 minutes) to reduce LLM API calls and cost.
- **Embedding ingestion:** Scheduled job embeds product catalogs, SOPs, and warehouse procedures into pgvector. Re-embed on document update.
- **Monitoring:** Track `karyo_ai_queries_total`, `karyo_ai_tokens_used_total`, `karyo_ai_latency_seconds` via Prometheus.
- **Rate limiting:** Limit AI queries per user/tenant to prevent abuse. Kong rate limiting plugin on `/api/v1/ai/*` endpoints.

## Related Decisions
- [ADR-002: Quarkus as Microservices Framework](ADR-002-quarkus-framework.md)
- [ADR-005: PostgreSQL as Primary Database](ADR-005-postgresql-database.md) (pgvector)
- [ADR-016: Claude API as Primary LLM](ADR-016-claude-api-llm.md)

## References
- LangChain4j: https://github.com/langchain4j/langchain4j
- Quarkus LangChain4j: https://docs.quarkiverse.io/quarkus-langchain4j/dev/index.html
- LangChain4j pgvector: https://docs.langchain4j.dev/integrations/embedding-stores/pgvector
- Quarkus AI guide: https://quarkus.io/guides/ai

## Revision History
- 2026-02-15: Initial version
