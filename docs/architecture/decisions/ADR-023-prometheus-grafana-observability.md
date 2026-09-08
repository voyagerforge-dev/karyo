# ADR-023: Prometheus + Grafana for Observability

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md) — Instrumented, but NOT deployed.
>
> The app exposes Prometheus metrics at `/q/metrics` via Micrometer. **Nothing scrapes them.** No
> Prometheus server, no Grafana, no Loki, and no Alertmanager are deployed — the "deployed as part of
> the Kubernetes stack" premise does not apply to a 4-container Compose deployment.
>
> This remains the intended target. Standing up the stack is open work; until then, treat every
> dashboard and alert rule described here as **planned, not live**.


## Status
Partially superseded

## Context
Karyo WMS targets 99.99% uptime for 24/7 warehouse operations, with strict performance requirements (sub-200ms API response times at p95, 10K+ orders/hour throughput). Achieving these targets requires comprehensive observability including:

- **Metrics collection**: CPU, memory, request latency, error rates, business metrics (orders/hour, picks/minute)
- **Dashboards**: Real-time visualization for warehouse operators, managers, and system administrators
- **Alerting**: Proactive notification when SLAs are at risk or system health degrades
- **Multi-deployment support**: Must work on cloud Kubernetes, on-premise K3s, and edge nodes

The observability stack must also support the edge deployment model where a local monitoring solution runs independently of cloud connectivity, with optional aggregation to a central cloud dashboard when connected.

## Decision
We will use **Prometheus** for metrics collection and **Grafana** for dashboards and visualization, deployed as part of the Karyo infrastructure stack on every Kubernetes cluster.

**Architecture:**

```
┌─────────────────────────────────────────────────────┐
│                 Kubernetes Cluster                    │
│                                                       │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐           │
│  │inventory- │  │ order-   │  │  task-   │  ...      │
│  │ service   │  │ service  │  │ service  │           │
│  │ /q/metrics│  │/q/metrics│  │/q/metrics│           │
│  └─────┬─────┘  └─────┬────┘  └─────┬────┘           │
│        │               │              │               │
│        └───────────────┼──────────────┘               │
│                        │ scrape                       │
│                   ┌────▼─────┐                        │
│                   │Prometheus │                        │
│                   │  (TSDB)  │                        │
│                   └────┬─────┘                        │
│                        │                              │
│              ┌─────────┼──────────┐                   │
│              │         │          │                    │
│         ┌────▼───┐ ┌───▼────┐ ┌──▼──────┐           │
│         │Grafana │ │Alert-  │ │Thanos/  │           │
│         │        │ │manager │ │Cortex   │           │
│         │Dashbds │ │        │ │(optional│           │
│         └────────┘ │PagerDty│ │ cloud   │           │
│                    │ Slack  │ │ aggr.)  │           │
│                    └────────┘ └─────────┘           │
└─────────────────────────────────────────────────────┘
```

**Metrics Instrumentation (Quarkus Micrometer):**

```kotlin
// Quarkus auto-instruments: HTTP requests, JPA queries, Kafka consumers, JVM metrics
// application.properties:
// quarkus.micrometer.export.prometheus.enabled=true
// quarkus.micrometer.binder.http-server.enabled=true
// quarkus.micrometer.binder.jvm=true
// quarkus.micrometer.binder.kafka.enabled=true

// Custom business metrics
@ApplicationScoped
class InventoryMetrics(
    private val meterRegistry: MeterRegistry,
) {
    private val stockOperationCounter = Counter.builder("karyo_stock_operations_total")
        .description("Total stock operations by type and result")
        .tag("service", "inventory-service")
        .register(meterRegistry)

    private val reservationTimer = Timer.builder("karyo_stock_reservation_duration_seconds")
        .description("Time to complete a stock reservation")
        .publishPercentiles(0.5, 0.95, 0.99)
        .register(meterRegistry)

    fun recordStockOperation(operationType: String, result: String) {
        Counter.builder("karyo_stock_operations_total")
            .tag("operation", operationType)  // "reserve", "pick", "transfer", "adjust"
            .tag("result", result)            // "success", "insufficient_stock", "locked"
            .register(meterRegistry)
            .increment()
    }

    fun <T> timeReservation(block: () -> T): T = reservationTimer.record(block)!!
}
```

**Standard Metrics per Service:**

| Metric | Type | Labels | Description |
|--------|------|--------|-------------|
| `karyo_api_requests_total` | Counter | service, endpoint, method, status | Total API requests |
| `karyo_api_latency_seconds` | Histogram | service, endpoint, method | Request latency (p50/p95/p99) |
| `karyo_orders_processed_total` | Counter | service, type, state | Orders processed |
| `karyo_stock_operations_total` | Counter | service, operation, result | Stock operations |
| `karyo_kafka_messages_total` | Counter | service, topic, direction | Kafka messages sent/received |
| `karyo_kafka_consumer_lag` | Gauge | service, topic, partition | Consumer lag |
| `karyo_db_connections_active` | Gauge | service, pool | Active DB connections |
| `karyo_db_query_duration_seconds` | Histogram | service, query_type | Database query duration |
| `karyo_cache_hits_total` | Counter | service, cache_name | Cache hits |
| `karyo_cache_misses_total` | Counter | service, cache_name | Cache misses |

**Alerting Rules (Prometheus Alertmanager):**

```yaml
groups:
  - name: karyo-sla
    rules:
      - alert: HighApiLatency
        expr: histogram_quantile(0.95, rate(karyo_api_latency_seconds_bucket[5m])) > 0.2
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "API p95 latency exceeds 200ms on {{ $labels.service }}"

      - alert: HighErrorRate
        expr: rate(karyo_api_requests_total{status=~"5.."}[5m]) / rate(karyo_api_requests_total[5m]) > 0.01
        for: 2m
        labels:
          severity: critical
        annotations:
          summary: "Error rate > 1% on {{ $labels.service }}"

      - alert: KafkaConsumerLag
        expr: karyo_kafka_consumer_lag > 10000
        for: 10m
        labels:
          severity: warning
        annotations:
          summary: "Kafka consumer lag > 10K on {{ $labels.service }}/{{ $labels.topic }}"

      - alert: LowOrderThroughput
        expr: rate(karyo_orders_processed_total{state="FINISHED"}[1h]) * 3600 < 5000
        for: 30m
        labels:
          severity: warning
        annotations:
          summary: "Order throughput below 5K/hour during business hours"
```

**Grafana Dashboard Structure:**

| Dashboard | Audience | Key Panels |
|-----------|----------|-----------|
| System Overview | SRE/Admin | All services health, cluster resources, error rates |
| Inventory Operations | Manager | Stock levels, movements/hour, adjustment frequency |
| Order Fulfillment | Manager | Orders/hour, pick rates, shipping SLA compliance |
| Service Detail (per service) | Developer | Latency histograms, error breakdown, DB pool, cache stats |
| Kafka Health | SRE | Consumer lag, message rates, partition distribution |
| Business KPIs | Executive | Daily throughput, SLA compliance, warehouse utilization |

**Alert Routing:**

| Severity | Route | Response Time |
|----------|-------|--------------|
| Critical | PagerDuty (on-call) + Slack #karyo-alerts | < 15 min |
| Warning | Slack #karyo-alerts | < 1 hour |
| Info | Slack #karyo-monitoring | Best effort |

## Consequences

### Positive
- Pull-based scraping model naturally discovers services in Kubernetes via service discovery annotations — no agent configuration needed per service
- Quarkus Micrometer extension provides automatic instrumentation of HTTP, JPA, Kafka, and JVM metrics with zero code changes
- Prometheus + Grafana are the de facto standard for Kubernetes monitoring — extensive community dashboards, alerting rules, and documentation
- Open source with no per-host or per-metric licensing — cost-effective at scale and on edge deployments
- Local monitoring works independently of cloud connectivity — edge warehouses maintain full observability during disconnection
- PromQL (Prometheus Query Language) is powerful and well-documented for complex queries
- Grafana supports alerting, annotations, and dashboard-as-code (JSON models stored in GitOps repo)

### Negative
- Prometheus is a single-node system by default — long-term storage and high availability require additional components (Thanos or Cortex)
- Prometheus storage is local to the node — disk space planning is required, and data loss occurs if the node fails without HA
- Pull-based model requires services to expose a metrics endpoint — short-lived batch jobs may not be scraped before completing
- Grafana dashboard maintenance — dashboards drift from reality if not kept in sync with service changes
- Alert fatigue risk — poorly tuned alerts can overwhelm on-call staff; alert rules require ongoing tuning

### Neutral
- For multi-cluster aggregation (central cloud dashboard viewing all edge sites), Thanos or Grafana Mimir can be added as a federation layer without changing the per-cluster Prometheus setup
- Prometheus retention defaults to 15 days; extend via Thanos/Mimir for long-term storage or use Prometheus remote-write to push data to a central store
- Grafana Cloud is available as a managed option for teams that prefer SaaS; compatible with the self-hosted Prometheus instances

## Alternatives Considered

### Alternative 1: Datadog
- **Pros**: Fully managed SaaS, unified metrics/logs/traces in one platform, advanced ML-based alerting, extensive integrations, excellent UX
- **Cons**: Per-host pricing becomes expensive at scale ($15-23/host/month), vendor lock-in with proprietary query language, cannot run on-premise or at the edge, data residency concerns, no self-hosted option
- **Why rejected**: Cost at scale is prohibitive — with 9 microservices scaled to multiple replicas across cloud + edge deployments, per-host pricing adds up quickly. Cannot run on edge nodes without cloud connectivity. Vendor lock-in contradicts open-source commitment.

### Alternative 2: New Relic
- **Pros**: Generous free tier (100GB/month), full observability platform, AI-powered insights, easy setup
- **Cons**: Same SaaS-only limitations as Datadog, per-GB pricing above free tier, proprietary data format, cannot run on-premise, query language (NRQL) is vendor-specific
- **Why rejected**: Same fundamental issues as Datadog — SaaS-only, no edge support, vendor lock-in. Free tier may be sufficient initially but doesn't scale to production multi-tenant workloads.

### Alternative 3: Amazon CloudWatch
- **Pros**: Native AWS integration, managed service, auto-discovers AWS resources, reasonable pricing
- **Cons**: AWS-only — does not work on GKE, AKS, on-premise K3s, or edge deployments. Proprietary query language. Limited dashboard customization compared to Grafana. No self-hosted option.
- **Why rejected**: Karyo WMS targets multi-cloud and on-premise deployments. CloudWatch is only viable for AWS-hosted cloud deployments, creating a fragmented monitoring strategy across deployment models.

## Implementation Notes
- Deploy Prometheus and Grafana via the `kube-prometheus-stack` Helm chart (includes Prometheus Operator, Grafana, Alertmanager, default dashboards)
- Configure Quarkus services with `quarkus.micrometer.export.prometheus.path=/q/metrics` and add Kubernetes annotations for Prometheus service discovery:
  ```yaml
  annotations:
    prometheus.io/scrape: "true"
    prometheus.io/port: "8080"
    prometheus.io/path: "/q/metrics"
  ```
- Store Grafana dashboards as JSON in the GitOps repo (ADR-022) and provision them via Grafana's dashboard provisioning or ConfigMaps
- Configure Alertmanager receivers for PagerDuty and Slack in the GitOps repo (encrypted secrets via Sealed Secrets)
- For edge deployments, use a lightweight Prometheus configuration with shorter retention (7 days) to minimize disk usage
- Implement custom Micrometer `MeterBinder` classes for each service's business metrics
- Add tenant_id as a label to business metrics where appropriate, but be cautious of cardinality explosion — use tenant labels only on aggregate metrics, not per-request metrics
- Set up Prometheus recording rules for frequently queried aggregations to reduce dashboard load times

## Related Decisions
- [ADR-024](ADR-024-opentelemetry-distributed-tracing.md): OpenTelemetry for Distributed Tracing — traces complement metrics for debugging
- [ADR-025](ADR-025-structured-logging-json.md): Structured Logging — logs complement metrics for incident investigation
- [ADR-022](superseded/ADR-022-gitops-with-argocd.md): GitOps with ArgoCD — monitoring stack and dashboards deployed via GitOps

## References
- [Prometheus Documentation](https://prometheus.io/docs/)
- [Grafana Documentation](https://grafana.com/docs/)
- [Quarkus Micrometer Guide](https://quarkus.io/guides/micrometer)
- [kube-prometheus-stack Helm Chart](https://github.com/prometheus-community/helm-charts/tree/main/charts/kube-prometheus-stack)
- [SRE Book: Monitoring Distributed Systems](https://sre.google/sre-book/monitoring-distributed-systems/)

## Revision History
- 2026-02-15: Initial version
