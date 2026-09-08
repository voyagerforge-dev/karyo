# ADR-016: Claude API as Primary LLM

> **Implementation context (2026-09-08):** `KARYO_AI_PROVIDER` selects `anthropic`, `ollama` or
> `none` (default). There is no automatic cloud-to-local failover contract, and not every paid
> engine calls an LLM. Model pins/configuration, not "latest available" in the original design,
> govern a build. [ADR index](README.md).

## Status
Accepted

## Context
Karyo WMS's artificial-intelligence-service uses LangChain4j (see [ADR-015](ADR-015-langchain4j.md)) for LLM orchestration. An LLM provider must be selected for:

1. **Natural language warehouse queries:** Interpreting operator questions like "Show me all expired stock in the frozen zone" and translating them into tool calls that query Karyo service APIs.
2. **Tool use / function calling:** The LLM must reliably identify which tools to call, construct correct parameters, and synthesize results into coherent responses. This is the most critical capability — an LLM that misidentifies tools or passes incorrect parameters would return wrong warehouse data.
3. **Structured output:** Parsing LLM responses into typed objects for demand forecasting predictions and slotting recommendations.
4. **Document parsing:** Extracting structured data from bills of lading, purchase orders, and packing slips (unstructured PDF/image input).
5. **Reasoning about warehouse domain:** Understanding WMS concepts (FIFO, lot tracking, shelf life, putaway strategies) to provide contextually appropriate answers.

Key constraints:
- **Tool calling reliability:** The LLM must correctly call 4-8 tools with specific parameters (SKU numbers, zone names, date ranges). Incorrect parameters return wrong data, which is worse than no data.
- **Edge fallback:** Edge deployments without cloud connectivity need a local LLM option.
- **Cost management:** AI queries are ad-hoc (warehouse managers, not automated systems), so volume is moderate (hundreds to low thousands per day). Cost must be predictable and manageable.

## Decision
We will use **Anthropic Claude** (latest available model) as the primary LLM for all AI capabilities in Karyo WMS.

### Configuration

```properties
# Primary provider: Claude
quarkus.langchain4j.anthropic.api-key=${ANTHROPIC_API_KEY}
quarkus.langchain4j.anthropic.chat-model.model-name=claude-sonnet-4-20250514
quarkus.langchain4j.anthropic.chat-model.max-tokens=4096
quarkus.langchain4j.anthropic.chat-model.temperature=0.1  # Low temp for factual queries
quarkus.langchain4j.anthropic.timeout=30s

# Embedding model: separate from chat model
quarkus.langchain4j.anthropic.embedding-model.model-name=voyage-3  # Or equivalent
```

### Model Selection by Use Case

| Use Case | Model | Temperature | Max Tokens | Rationale |
|----------|-------|-------------|------------|-----------|
| NL warehouse queries | Claude Sonnet | 0.1 | 4096 | Fast, reliable tool calling, cost-effective |
| Complex analysis (slotting recommendations) | Claude Opus | 0.2 | 8192 | Deep reasoning for optimization suggestions |
| Document parsing (BOL, PO) | Claude Sonnet | 0.0 | 4096 | Structured extraction needs determinism |
| Demand forecasting narrative | Claude Sonnet | 0.3 | 2048 | Some creativity for trend interpretation |

### Edge Fallback: Ollama

For edge deployments without cloud connectivity:

```properties
# Edge fallback: Ollama (local)
quarkus.langchain4j.ollama.base-url=http://localhost:11434
quarkus.langchain4j.ollama.chat-model.model-name=llama3.1:8b
quarkus.langchain4j.ollama.chat-model.temperature=0.1
```

**Fallback logic:**
1. Primary: Claude API (cloud)
2. On network failure or timeout: Fall back to Ollama (local)
3. If Ollama unavailable: Return error with guidance to check connectivity

**Edge model limitations:**
- Smaller context window (8K vs 200K tokens)
- Less reliable tool calling (may require simpler tool descriptions)
- Lower quality for complex reasoning tasks
- Suitable for: basic NL queries, simple stock lookups
- Not suitable for: complex slotting analysis, document parsing

### Cost Management

| Strategy | Implementation |
|----------|---------------|
| **Query caching** | Cache LLM responses for identical queries (key: query hash + tool results hash). TTL: 5 minutes. |
| **Token budget per tenant** | Track tokens used per tenant per month. Configurable limit (e.g., 1M tokens/month). Alert at 80%, block at 100%. |
| **Batch embeddings** | Run embedding jobs in batch (off-peak hours) rather than per-document to reduce API call overhead. |
| **Model routing** | Use Sonnet (cheaper) for simple queries; route to Opus (expensive) only for complex analysis explicitly requested by managers. |
| **Prompt caching** | Leverage Claude's prompt caching for system prompts and RAG context that repeat across queries. |

### Monitoring

| Metric | Type | Labels |
|--------|------|--------|
| `karyo_ai_queries_total` | Counter | model, status (success/error/fallback), tenant |
| `karyo_ai_tokens_input_total` | Counter | model, tenant |
| `karyo_ai_tokens_output_total` | Counter | model, tenant |
| `karyo_ai_latency_seconds` | Histogram | model, query_type |
| `karyo_ai_tool_calls_total` | Counter | tool_name, status |
| `karyo_ai_cache_hits_total` | Counter | — |
| `karyo_ai_fallback_total` | Counter | reason (network, timeout, error) |

## Consequences

### Positive
- **Superior tool calling:** Claude has strong function calling capabilities, critical for the warehouse query use case where the LLM must correctly identify and parameterize tools (stock queries, order lookups, location utilization). Incorrect tool calling would return wrong warehouse data.
- **Large context window:** Claude's 200K token context window supports large RAG contexts (multiple SOP sections, product catalogs) without truncation, improving answer quality for complex queries.
- **Strong reasoning:** Claude excels at multi-step reasoning, valuable for complex analysis ("Why is this zone underutilized?" requires correlating inventory data, order patterns, and layout configuration).
- **Structured output reliability:** Claude follows instructions for structured output (JSON, specific formats) with high reliability, reducing parsing errors for predictions and recommendations.
- **Prompt caching:** Claude's prompt caching reduces cost and latency for repeated system prompts and RAG contexts.
- **LangChain4j integration:** The `langchain4j-anthropic` module provides native Claude support with tool calling, streaming, and vision (for document parsing).

### Negative
- **Cloud dependency:** Claude API requires internet connectivity. Edge deployments without cloud access must fall back to Ollama with reduced capabilities. The AI service is already designated cloud-only, but intermittent connectivity degrades the experience.
- **API cost:** Claude API charges per token. At moderate usage (1000 queries/day, average 2000 input + 500 output tokens each), monthly cost is approximately $50-200/month (Sonnet). Opus for complex analysis is 5-10x more expensive. Mitigated by caching, model routing, and token budgets.
- **Vendor dependency:** Reliance on Anthropic's API availability and pricing. Mitigated by LangChain4j's multi-provider abstraction — switching to OpenAI or another provider requires only configuration changes.
- **Latency:** Cloud API calls add 1-5 seconds per query (network + model inference). Acceptable for ad-hoc manager queries but too slow for automated high-frequency operations. Warehouse operational algorithms (stock selection, location finding) explicitly use deterministic algorithms, not LLM calls.

### Neutral
- Claude's knowledge cutoff means it does not know about warehouse-specific procedures or recent product changes. RAG provides this context by embedding domain-specific documents into pgvector and including relevant context in the prompt.
- The edge fallback (Ollama) provides basic AI capability without cloud connectivity but at reduced quality. This is an acceptable trade-off for the edge deployment scenario.

## Alternatives Considered

### Alternative 1: OpenAI GPT-4 / GPT-4o
- **Pros**: Largest ecosystem, most production references, GPT-4o is fast and cost-effective, extensive documentation, Azure deployment option for enterprise customers.
- **Cons**: Tool calling reliability is comparable but Claude's structured output following is slightly more consistent in internal testing, no significant technical advantage for the WMS domain, vendor concentration risk (already using Anthropic for development tooling with Claude Code).
- **Why rejected**: Both Claude and GPT-4 are viable choices. Claude was selected because: (1) slightly better structured output adherence in WMS domain testing, (2) larger context window (200K vs 128K) for RAG with large SOP documents, (3) prompt caching reduces cost for repeated system prompts. If Claude API availability or pricing becomes problematic, GPT-4o is the first alternative and can be activated with a configuration change via LangChain4j.

### Alternative 2: Google Gemini
- **Pros**: Competitive pricing, large context window (1M tokens for Gemini 1.5 Pro), multimodal capabilities, Google Cloud integration.
- **Cons**: Tool calling (function calling) is less mature than Claude and GPT-4 in production, fewer production references for enterprise tool-calling use cases, Quarkus LangChain4j integration for Gemini is less established than for Claude/OpenAI.
- **Why rejected**: Tool calling reliability is the most critical factor for the warehouse query use case. Gemini's function calling, while improving, has fewer production references for the type of multi-tool, parameter-rich interactions that Karyo requires (querying stock by SKU, filtering by zone, checking expiry dates). May be reconsidered as Gemini's tool calling matures.

### Alternative 3: Local-Only Models (Ollama / llama.cpp)
- **Pros**: No cloud dependency, no API costs, no data leaving the premises, runs on edge hardware.
- **Cons**: Significantly lower quality for complex reasoning and tool calling, larger models (70B+ parameters) require GPU or high-RAM servers (not available at edge), smaller models (7-8B parameters) struggle with reliable tool calling and structured output, no RAG embedding models comparable to cloud offerings.
- **Why rejected**: Current open-source models (Llama 3.1 8B, Mistral 7B) cannot reliably execute the multi-tool warehouse query patterns required by Karyo. They frequently misidentify tools, pass incorrect parameters, and produce less coherent responses. Local models are retained as a fallback for basic queries during cloud connectivity loss, but cannot serve as the primary LLM. This assessment may change as local models improve.

## Implementation Notes
- **API key management:** Store `ANTHROPIC_API_KEY` in Kubernetes Secret (Sealed Secrets or External Secrets Operator). Never commit to Git.
- **Rate limiting:** Apply Kong rate limiting on `/api/v1/ai/*` endpoints: 10 req/min per user (configurable).
- **Token budget tracking:** Implement `TokenBudgetService` that persists per-tenant token usage in `karyo_ai.token_usage` table. Check budget before each LLM call.
- **Query logging:** Log all AI queries and responses to `karyo_ai.query_logs` table for audit, quality improvement, and cost analysis. Redact any PII in logs.
- **Prompt engineering:** Maintain system prompts as versioned resources in the artificial-intelligence-service codebase. A/B test prompt variations to improve tool calling accuracy.
- **Health check:** Include Claude API connectivity in the artificial-intelligence-service readiness probe. If Claude is unreachable, service reports "degraded" (Ollama fallback available) or "unavailable" (no fallback).

## Related Decisions
- [ADR-005: PostgreSQL as Primary Database](ADR-005-postgresql-database.md) (pgvector for RAG)
- [ADR-015: LangChain4j for LLM Orchestration](ADR-015-langchain4j.md)

## References
- Anthropic Claude API: https://docs.anthropic.com/
- Claude tool use: https://docs.anthropic.com/en/docs/build-with-claude/tool-use
- Claude prompt caching: https://docs.anthropic.com/en/docs/build-with-claude/prompt-caching
- Ollama: https://ollama.com/
- LangChain4j Anthropic module: https://docs.langchain4j.dev/integrations/language-models/anthropic

## Revision History
- 2026-02-15: Initial version
