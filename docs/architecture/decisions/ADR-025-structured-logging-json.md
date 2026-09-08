# ADR-025: Structured Logging with JSON Format

> **Implementation status:** See
> [observability](../observability-architecture.md#2-structured-logging) for current logging
> configuration, emitted fields and collection limits. The body retains the original design,
> including planned aggregation and trace fields; its examples are not deployment configuration.

## Status
Accepted

## Context
Karyo WMS is a distributed microservices system where a single business operation may span multiple services, Kafka topics, and database transactions. Effective incident investigation requires correlating log entries across services, linking logs to traces and metrics, and enabling efficient search and aggregation over large volumes of log data.

Key logging requirements:
- **Cross-service correlation**: A single order fulfillment generates logs in order-service, inventory-service, and task-service — these must be correlatable
- **Machine-parseable**: Log aggregation systems must parse log entries without fragile regex patterns
- **Tenant-aware**: In a multi-tenant system (ADR-020), logs must identify which tenant's operation generated the entry
- **Compliance**: Warehouse operations in regulated industries (FDA 21 CFR Part 11) require audit-quality logging
- **Edge-compatible**: Logging must work on resource-constrained edge nodes
- **Developer-friendly**: Readable during local development

## Decision
We will use **JSON structured logging** to stdout as the standard log format for all Karyo services, with **Grafana Loki** as the log aggregation backend.

**Log Format (JSON to stdout):**

```json
{
  "timestamp": "2026-02-15T10:30:00.123Z",
  "level": "INFO",
  "logger": "com.karyo.inventory.service.StockService",
  "service": "inventory-service",
  "version": "1.2.3",
  "traceId": "abc123def456789",
  "spanId": "span789abc",
  "tenantId": 42,
  "userId": "jdoe",
  "message": "Stock reserved successfully",
  "context": {
    "stockUnitId": 1001,
    "amount": 30.0,
    "orderId": "ORD-2026-0042",
    "locationName": "A-01-02-03"
  }
}
```

**Required Fields:**

| Field | Type | Source | Description |
|-------|------|--------|-------------|
| `timestamp` | ISO 8601 | System | When the event occurred (UTC) |
| `level` | String | Logger | ERROR, WARN, INFO, DEBUG |
| `logger` | String | Logger | Fully qualified class name |
| `service` | String | Config | Service name (e.g., `inventory-service`) |
| `version` | String | Config | Service version (semver) |
| `traceId` | String | OTel context | Distributed trace ID (from ADR-024) |
| `spanId` | String | OTel context | Current span ID |
| `tenantId` | Long | Request context | Tenant identifier (from ADR-020) |
| `userId` | String | JWT | Authenticated user identifier |
| `message` | String | Developer | Human-readable event description |
| `context` | Object | Developer | Structured key-value pairs specific to the log event |

**Quarkus Configuration:** See the
[current logging configuration](../observability-architecture.md#2-structured-logging) rather than
using the original scalar JSON toggle or assuming the proposed extra fields are assembled.

**Log Level Guidelines:**

| Level | Usage | Production State | Alert Action |
|-------|-------|-----------------|-------------|
| ERROR | Unrecoverable failure, data integrity risk, service down | Enabled | P0 — immediate investigation |
| WARN | Degraded operation, retry needed, approaching limits | Enabled | P1 — investigate within 1 hour |
| INFO | Business events, state transitions, operational milestones | Enabled | No alert — operational visibility |
| DEBUG | Method entry/exit, variable values, query details | Disabled | N/A — enable per-service for debugging |

**Logging Patterns by Service Layer:**

```kotlin
// REST resource layer — log request/response summary
@Path("/api/v1/stock-units")
class StockUnitResource(private val stockService: StockService) {
    private val logger = Logger.getLogger(StockUnitResource::class.java)

    @POST
    @Path("/{id}/reserve")
    fun reserve(@PathParam("id") id: Long, request: ReserveRequest): Response {
        logger.info("Stock reservation requested",
            mapOf("stockUnitId" to id, "amount" to request.amount, "orderId" to request.orderId))

        return when (val result = stockService.reserveStock(id, request.amount)) {
            is PickResult.Success -> {
                logger.info("Stock reservation succeeded",
                    mapOf("stockUnitId" to id, "reserved" to request.amount))
                Response.ok(result).build()
            }
            is PickResult.InsufficientStock -> {
                logger.warn("Stock reservation failed: insufficient stock",
                    mapOf("stockUnitId" to id, "requested" to request.amount, "available" to result.available))
                Response.status(409).entity(result).build()
            }
        }
    }
}

// Service layer — log business decisions and state transitions
@ApplicationScoped
class StockService {
    private val logger = Logger.getLogger(StockService::class.java)

    @Transactional
    fun reserveStock(stockUnitId: Long, amount: BigDecimal): PickResult {
        val stock = stockRepo.findByIdForTenant(stockUnitId)
            ?: run {
                logger.error("Stock unit not found", mapOf("stockUnitId" to stockUnitId))
                throw NotFoundException("Stock unit $stockUnitId not found")
            }

        if (stock.availableAmount < amount) {
            return PickResult.InsufficientStock(stock.availableAmount)
        }

        val oldReserved = stock.reservedAmount
        stock.reservedAmount = stock.reservedAmount.add(amount)

        logger.info("Stock reservation applied",
            mapOf(
                "stockUnitId" to stockUnitId,
                "oldReserved" to oldReserved,
                "newReserved" to stock.reservedAmount,
                "availableAfter" to stock.availableAmount,
            ))

        return PickResult.Success(amount)
    }
}
```

**Log Aggregation — Grafana Loki:**

```
┌──────────────┐     stdout      ┌──────────────┐     push      ┌──────────┐
│  Karyo       │────────────────►│  Promtail    │──────────────►│  Loki    │
│  Services    │  JSON to stdout │  (DaemonSet) │  (log stream) │          │
└──────────────┘                 └──────────────┘               └─────┬────┘
                                                                      │
                                                                 ┌────▼────┐
                                                                 │ Grafana │
                                                                 │ (query) │
                                                                 └─────────┘
```

- **Promtail** (DaemonSet on each node): Tails container stdout logs, adds Kubernetes labels (pod, namespace, container), pushes to Loki
- **Loki**: Indexes log labels (not full-text), stores compressed log chunks in object storage (S3/MinIO)
- **Grafana**: LogQL queries for searching, filtering, and aggregating logs; link to traces via traceId

**Log Retention:**
- Hot storage (Loki): 30 days — full query capability
- Cold storage (S3/MinIO): 90 days — archival, retrievable for compliance investigations
- Compliance archives (if required): 7 years — compressed, encrypted, in compliance storage

**Sensitive Data Handling:**
- Never log passwords, tokens, API keys, or personal data (PII)
- Mask sensitive fields: customer names logged as initials, addresses as city only
- SerialNumbers and lot numbers are operational data and may be logged
- Implement a Quarkus log filter that redacts known sensitive patterns (JWT tokens, Authorization headers)

## Consequences

### Positive
- JSON format is natively parseable by all log aggregation systems — no regex parsing, no broken multi-line log entries
- Structured fields enable powerful queries: "show all ERROR logs for tenantId=42 in the last hour" without text search
- TraceId in every log entry enables one-click navigation from logs to distributed traces in Grafana (ADR-024)
- Stdout logging follows the 12-factor app methodology — the application does not manage log files, rotation, or shipping
- Grafana Loki integrates natively with the existing Grafana dashboards (ADR-023), providing a unified observability experience
- Loki's label-based indexing is significantly lighter than Elasticsearch's full-text indexing, reducing storage costs and operational overhead
- Same logging configuration works on cloud and edge deployments

### Negative
- JSON logs are less human-readable than plain text during local development (mitigated by using plain text format in dev profile)
- Structured logging requires discipline — developers must use the structured API consistently rather than string interpolation
- Loki's label-based indexing means full-text search across log content is slower than Elasticsearch — use labels for filtering, then scan content
- Log volume in a high-throughput warehouse (10K+ orders/hour) can be significant — requires careful log level management and retention policies
- Adding context fields to every log entry adds code verbosity compared to unstructured logging

### Neutral
- If Loki's query performance proves insufficient for complex log analysis, Elasticsearch can be added as a secondary index for specific use cases (e.g., compliance audit queries) without changing the logging format
- Quarkus supports MDC (Mapped Diagnostic Context) for automatically including tenantId and userId in all log entries within a request scope, reducing per-log-statement boilerplate

## Alternatives Considered

### Alternative 1: ELK Stack (Elasticsearch + Logstash + Kibana)
- **Pros**: Full-text search over log content, mature ecosystem, powerful Kibana visualizations, established standard for log analytics
- **Cons**: Heavy resource footprint (Elasticsearch requires significant RAM and disk), operational complexity (cluster management, index lifecycle, shard allocation), higher storage cost due to full-text indexing, separate UI from Grafana (fragmented observability experience)
- **Why rejected**: Loki provides sufficient log querying for operational needs with significantly lower resource requirements. The integration with Grafana (already used for metrics, ADR-023) provides a unified observability experience. Full-text indexing overhead is not justified when structured JSON fields cover the primary query patterns.

### Alternative 2: Splunk
- **Pros**: Industry-leading log analysis, powerful SPL query language, enterprise support, compliance-ready
- **Cons**: Commercial licensing (per-GB ingestion pricing), expensive at warehouse log volumes, no self-hosted option for edge, vendor lock-in
- **Why rejected**: Cost is prohibitive for the expected log volume of a high-throughput warehouse system. Cannot run on edge deployments. Vendor lock-in contradicts open-source commitment.

### Alternative 3: Fluentd/Fluent Bit Pipeline (without Loki)
- **Pros**: Flexible log routing and transformation, CNCF project, extensive output plugin ecosystem
- **Cons**: Fluentd is a log pipeline, not a storage or query solution — still needs a backend (Elasticsearch, Loki, CloudWatch). Adds pipeline complexity without solving the storage/query problem. Configuration can be complex for advanced routing rules.
- **Why rejected**: Fluentd/Fluent Bit solves log shipping but not storage or querying. Promtail + Loki provides an integrated shipping + storage + query solution with simpler configuration. If advanced log transformation is needed in the future, Fluent Bit can be added between services and Loki.

## Implementation Notes
- Configure Quarkus `quarkus-logging-json` extension in each service; use profile-specific configuration to keep plain text in dev and JSON in staging/prod
- Implement a `TenantLoggingFilter` (JAX-RS `ContainerRequestFilter`) that sets `tenantId` and `userId` in the MDC at the start of each request, so all log entries within the request automatically include these fields
- Create a `LogContext` utility class with a builder pattern for structured context fields:
  ```kotlin
  logger.info("Stock reserved", LogContext.of("stockUnitId", id, "amount", amount))
  ```
- Deploy Loki via the `loki-stack` Helm chart, configured with S3/MinIO backend for log chunk storage
- Create Grafana Explore dashboards with saved queries for common investigations: errors by service, slow requests by tenant, order lifecycle timeline
- Set up log-based alerts in Grafana for critical patterns: `level="ERROR" AND service="inventory-service"` triggers PagerDuty
- For edge deployments, configure Promtail with a local buffer to handle temporary Loki unavailability; logs are buffered and replayed when connectivity returns

## Related Decisions
- [ADR-024](ADR-024-opentelemetry-distributed-tracing.md): OpenTelemetry — traceId/spanId fields in logs enable trace correlation
- [ADR-023](ADR-023-prometheus-grafana-observability.md): Prometheus + Grafana — Grafana provides unified view of metrics, traces, and logs
- [ADR-020](ADR-020-multi-tenancy-strategy.md): Multi-Tenancy — tenantId in logs enables per-tenant log filtering

## References
- [Quarkus Logging JSON Extension](https://quarkus.io/guides/logging#json-logging)
- [Grafana Loki Documentation](https://grafana.com/docs/loki/)
- [12-Factor App: Logs](https://12factor.net/logs)
- [LogQL Query Language](https://grafana.com/docs/loki/latest/logql/)

## Revision History
- 2026-02-15: Initial version
