# ADR-024: OpenTelemetry for Distributed Tracing

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md) — Premise void AND the SDK is switched off.
>
> The stated motivation — "a single user operation can span multiple services" — no longer applies: an
> operation is one in-process transaction.
>
> More importantly, **OpenTelemetry is explicitly disabled in every profile Karyo runs**
> (`quarkus.otel.enabled: false` in `%dev`, `quarkus.otel.sdk.disabled: true` in `%prod`). **No traces
> are produced anywhere.** What actually provides cross-step traceability today is the correlation id:
> threaded from `X-Correlation-Id` through MDC into `InventoryJournal` rows, queryable at
> `GET /api/v1/journals?correlationId=`. Distributed tracing regains its point only if a module is
> extracted.


## Status
Partially superseded

## Context
Karyo WMS's microservices architecture means a single user operation (e.g., confirming a pick) can span multiple services: order-service updates the pick order, calls inventory-service to transfer stock, which emits a Kafka event consumed by task-service. When performance degrades or errors occur, identifying the root cause across service boundaries requires distributed tracing.

Key scenarios requiring distributed tracing:
- **Latency diagnosis**: A picking operation takes 3 seconds — is the bottleneck in order-service logic, inventory-service database query, or Kafka message delivery?
- **Error correlation**: A shipping confirmation fails — was it a stock state issue in inventory-service or a validation error in order-service?
- **Cross-service dependency mapping**: Understanding the actual call graph between services in production
- **SLA monitoring**: Tracking end-to-end latency for critical workflows (goods receipt, pick-pack-ship)

The tracing solution must be vendor-neutral to avoid lock-in and must work across both synchronous (REST/gRPC) and asynchronous (Kafka) communication patterns.

## Decision
We will use **OpenTelemetry (OTel)** as the instrumentation standard and **Jaeger** as the trace backend.

**Architecture:**

```
┌──────────────┐     W3C Traceparent      ┌──────────────┐
│ order-service │──────────────────────────►│  inventory-  │
│               │     REST/gRPC header     │   service    │
│  OTel SDK     │                          │  OTel SDK    │
└──────┬────────┘                          └──────┬───────┘
       │                                          │
       │  OTLP export                             │  OTLP export
       │                                          │
       ▼                                          ▼
┌─────────────────────────────────────────────────────────┐
│                  OTel Collector (optional)                │
│              Batching, sampling, export                   │
└────────────────────────┬────────────────────────────────┘
                         │
                         ▼
                  ┌──────────────┐
                  │    Jaeger    │
                  │   Backend   │
                  │  (storage   │
                  │   + UI)     │
                  └──────────────┘
```

**Trace Context Propagation:**

| Communication | Propagation Method | Header/Field |
|--------------|-------------------|-------------|
| REST (synchronous) | W3C Trace Context | `traceparent`, `tracestate` HTTP headers |
| gRPC (synchronous) | W3C Trace Context | gRPC metadata |
| Kafka (asynchronous) | W3C Trace Context | Kafka record headers |

```kotlin
// Kafka producer: trace context injected automatically by OTel Kafka instrumentation
// Kafka consumer: trace context extracted, creating a span linked to the producer span

// Example: order-service produces an event, inventory-service consumes it
// The full trace shows: order-service → Kafka → inventory-service
// with timing for each leg
```

**Quarkus OpenTelemetry Configuration:**

```properties
# application.properties (per service)
quarkus.otel.enabled=true
quarkus.otel.exporter.otlp.endpoint=http://jaeger-collector:4317
quarkus.otel.exporter.otlp.protocol=grpc
quarkus.otel.service.name=${quarkus.application.name}

# Resource attributes
quarkus.otel.resource.attributes=service.namespace=karyo,deployment.environment=${KARYO_ENV:dev}

# Sampling configuration
quarkus.otel.traces.sampler=parentbased_traceidratio
quarkus.otel.traces.sampler.arg=${OTEL_SAMPLING_RATE:1.0}
```

**Sampling Strategy:**

| Environment | Sampling Rate | Rationale |
|------------|--------------|-----------|
| Development | 100% | Full visibility for debugging |
| Staging | 100% | Full visibility for pre-production validation |
| Production | 10% head-based (configurable) | Balance between visibility and storage/performance cost |
| Production (on error) | 100% | Always capture traces for failed requests |

```properties
# Production sampling: 10% of traces, but always sample errors
# Implemented via OTel Collector tail-based sampling or custom sampler
quarkus.otel.traces.sampler.arg=0.1
```

**Custom Span Attributes for Warehouse Context:**

```kotlin
// Add warehouse-specific attributes to spans for richer trace context
@ApplicationScoped
class TraceEnricher(
    private val tenantContext: TenantContext,
) {
    fun enrichSpan(span: Span) {
        span.setAttribute("karyo.tenant_id", tenantContext.tenantId)
        span.setAttribute("karyo.warehouse_id", tenantContext.warehouseId ?: "unknown")
    }
}

// Manual span creation for business-critical operations
@ApplicationScoped
class StockReservationService(
    @Inject private val tracer: Tracer,
) {
    fun reserveStock(stockUnitId: Long, amount: BigDecimal): PickResult {
        val span = tracer.spanBuilder("reserve-stock")
            .setAttribute("karyo.stock_unit_id", stockUnitId)
            .setAttribute("karyo.amount", amount.toDouble())
            .startSpan()

        return try {
            span.makeCurrent().use {
                // Business logic here
                doReserve(stockUnitId, amount)
            }
        } catch (e: Exception) {
            span.setStatus(StatusCode.ERROR, e.message ?: "Unknown error")
            span.recordException(e)
            throw e
        } finally {
            span.end()
        }
    }
}
```

**Correlation with Logs and Metrics:**

Trace IDs and span IDs are automatically injected into log entries (ADR-025) and can be used to correlate metrics with specific traces:

```json
{
  "timestamp": "2026-02-15T10:30:00Z",
  "level": "INFO",
  "service": "inventory-service",
  "traceId": "abc123def456",
  "spanId": "span789",
  "tenantId": 42,
  "message": "Stock reserved",
  "stockUnitId": 1001,
  "amount": 30.0
}
```

## Consequences

### Positive
- Vendor-neutral instrumentation — OpenTelemetry is the CNCF standard; switching trace backends (Jaeger to Tempo, Zipkin, or commercial APM) requires only configuration changes, no code changes
- Automatic instrumentation of HTTP, gRPC, JDBC, and Kafka via Quarkus OTel extension — most traces are generated without manual code
- W3C Trace Context ensures interoperability with any OTel-compatible system, including external services and third-party integrations
- Kafka trace propagation provides end-to-end visibility across asynchronous event flows — critical for debugging event-driven workflows
- Correlation of traces with logs and metrics enables rapid root-cause analysis (click from a slow metric to the trace to the log entry)
- Configurable sampling allows production use with manageable storage costs

### Negative
- Distributed tracing adds overhead to every request — OTel SDK, context propagation, and span export consume CPU and memory
- Jaeger requires storage (Elasticsearch, Cassandra, or BadgerDB) that must be managed and scaled
- 10% sampling in production means 90% of traces are lost — intermittent issues may not be captured unless they persist across multiple requests
- Manual span creation for business logic adds code complexity and must be maintained
- Trace data can be noisy — hundreds of spans per complex operation require filtering and aggregation skills to interpret

### Neutral
- Grafana Tempo is an alternative to Jaeger that integrates more tightly with the Grafana stack (ADR-023); may be evaluated as a replacement if Jaeger operational overhead is excessive
- OpenTelemetry also defines standards for metrics and logs export; we may consolidate metrics export through OTel Collector in the future, but currently use Prometheus scraping directly (ADR-023)
- Tail-based sampling (via OTel Collector) provides better sampling decisions than head-based but requires buffering complete traces before making a sampling decision

## Alternatives Considered

### Alternative 1: Jaeger Native SDK (OpenTracing)
- **Pros**: Mature, well-tested, tight integration with Jaeger backend
- **Cons**: OpenTracing is deprecated in favor of OpenTelemetry, vendor lock-in to Jaeger, no future development, migration to OTel will eventually be required
- **Why rejected**: OpenTracing is deprecated. OpenTelemetry is the CNCF standard successor that subsumes both OpenTracing and OpenCensus. Adopting a deprecated instrumentation standard would require future migration.

### Alternative 2: Zipkin
- **Pros**: Simpler architecture than Jaeger, lower resource requirements, well-established
- **Cons**: Fewer features than Jaeger (no adaptive sampling, less rich UI, limited dependency analysis), smaller active development community, B3 propagation format is non-standard (vs. W3C Trace Context)
- **Why rejected**: Jaeger provides richer features (adaptive sampling, dependency graph, performance comparison) that are valuable for a complex microservices system. W3C Trace Context (used by OTel natively) is the industry standard over B3 format.

### Alternative 3: Commercial APM (Datadog APM, New Relic, Dynatrace)
- **Pros**: Fully managed, ML-powered anomaly detection, auto-discovery, unified platform with metrics and logs, excellent UX
- **Cons**: SaaS-only — cannot run on-premise or at the edge, per-host pricing, vendor lock-in, proprietary agents and data formats, data residency concerns
- **Why rejected**: Same reasons as commercial alternatives rejected in ADR-023 (observability). Cannot run on edge deployments, vendor lock-in, cost at scale. OpenTelemetry + Jaeger provides comparable capabilities with full control.

## Implementation Notes
- Add `quarkus-opentelemetry` extension to each service's `build.gradle.kts`
- Deploy Jaeger via the Jaeger Operator Helm chart or standalone Docker image for development
- For production, deploy Jaeger with Elasticsearch backend for trace storage; consider Jaeger with Cassandra for higher throughput
- Configure the OTel Collector as a DaemonSet for batching, filtering, and exporting spans (reduces network overhead from individual services)
- Add Grafana datasource for Jaeger to enable trace links from Grafana dashboards (ADR-023)
- Implement error-based sampling override: all traces with error status are captured regardless of sampling rate (using OTel Collector tail-based sampling processor)
- For edge deployments, use Jaeger all-in-one with BadgerDB (in-memory + disk) for lightweight local trace storage with 24-hour retention
- Create standard Karyo span naming conventions: `{service}.{operation}` (e.g., `inventory.reserve-stock`, `orders.generate-pick-lines`)

## Related Decisions
- [ADR-023](ADR-023-prometheus-grafana-observability.md): Prometheus + Grafana — metrics complement traces; Grafana links to Jaeger traces
- [ADR-025](ADR-025-structured-logging-json.md): Structured Logging — traceId/spanId injected into log entries for correlation
- [ADR-021](superseded/ADR-021-mtls-for-service-communication.md): mTLS — service mesh provides additional trace data via Envoy/linkerd proxies

## References
- [OpenTelemetry Documentation](https://opentelemetry.io/docs/)
- [W3C Trace Context Specification](https://www.w3.org/TR/trace-context/)
- [Quarkus OpenTelemetry Guide](https://quarkus.io/guides/opentelemetry)
- [Jaeger Documentation](https://www.jaegertracing.io/docs/)
- [OpenTelemetry Collector Documentation](https://opentelemetry.io/docs/collector/)

## Revision History
- 2026-02-15: Initial version
