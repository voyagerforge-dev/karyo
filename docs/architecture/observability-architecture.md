# Karyo WMS - Observability Architecture

> **Project:** Karyo WMS (codename: Project Syzygy)
> **Version:** 2.0
> **Reviewed:** 2026-09-08
> **Status:** Current mechanism/limitation reference; configuration is not proof of emitted data

---

## Table of Contents

1. [Observability Strategy](#1-observability-strategy)
2. [Structured Logging](#2-structured-logging)
3. [Metrics](#3-metrics)
4. [Distributed Tracing](#4-distributed-tracing)
5. [Health Checks](#5-health-checks)
6. [The Audit Trail as an Observability Surface](#6-the-audit-trail-as-an-observability-surface)
7. [Planned / Not Yet Deployed](#7-planned--not-yet-deployed)

---

## 1. Observability Strategy

Karyo WMS runs as a **single modular-monolith process** (`karyo-app`) since the June 2026 pivot
([ADR-037](decisions/ADR-037-modular-monolith.md)). This document describes what that process
actually emits today and draws a hard line between two very different things:

- **Instrumented in the app** — code paths exist, extensions are on the classpath, data is
  produced (or, in one case, deliberately turned off).
- **Collected / visualized** — something outside the app scrapes, stores, or renders that data.

As of 2026-07-19, almost everything below lives in the first bucket. **There is no Prometheus
server, no Grafana, no Loki, and no trace collector deployed anywhere in the stack.** The gap
between "instrumented" and "observed" is the single most important fact in this document — read
each section with that in mind before assuming a dashboard or alert exists.

### What is actually there

| Concern | Status |
|---------|--------|
| Structured JSON logs to stdout | Enabled in production; see [Structured Logging](#2-structured-logging) |
| Prometheus-format metrics | ✅ Exposed at `/q/metrics` (Micrometer + `quarkus-micrometer-registry-prometheus`); **nothing scrapes it** |
| OpenTelemetry tracing | ⚠️ Extension (`quarkus-opentelemetry`) is on the classpath, but the SDK is **explicitly disabled** in both the dev profile (`quarkus.otel.enabled: false`) and the prod profile (`quarkus.otel.sdk.disabled: true`) — see `services/karyo-app/src/main/resources/application.yaml`. No traces are produced in either environment Karyo actually runs in today. |
| Health checks | ✅ SmallRye Health at `/q/health`, proxied by nginx (only that one path — the rest of `/q/` stays private) |
| Correlation IDs | `X-Correlation-Id` request header populates MDC; journal-writing paths carry correlation separately. MDC alone does not guarantee the log formatter emits it |
| Dashboards / alerting / log aggregation | ❌ Not deployed |

### Deployment shape

Karyo runs as **4 containers via Docker Compose** (`karyo-app`, `postgresql`, `keycloak`,
`nginx` — see `infrastructure/docker/docker-compose.prod.yml`), not Kubernetes. There is no
cluster, no ServiceMonitor CRDs, no DaemonSet log shipper, no pod-level collection of any kind.
Any future collection stack has to be bolted onto Compose (or a future orchestrator), not
inherited from a cluster that doesn't exist.

### Correlation strategy (what's real today)

```
HTTP request
  → X-Correlation-Id header (client-supplied; TenantFilter reads it, no fallback generation)
  → TenantFilter (libs/karyo-security) populates MDC: tenantId, userId, correlationId
  → MDC is available to the configured log formatter (emission depends on the formatter)
  → Journal-writing operations carry correlationId into their InventoryJournal rows
  → GET /api/v1/journals?correlationId=... replays every journal row for one operation
```

A supplied `correlationId` links the journal entries that writing paths produce. Log correlation
also requires a formatter that emits the MDC field; see [Structured Logging](#2-structured-logging).
This is separate from OpenTelemetry span propagation.

---

## 2. Structured Logging

### Format

`karyo-app` assembles the `quarkus-logging-json` extension. The
[`application.yaml`](../../services/karyo-app/src/main/resources/application.yaml) console setting
is `quarkus.log.console.json.enabled`: false by default for human-readable dev/test output, true
under `%prod` for JSON stdout. The former scalar `quarkus.log.console.json` is not the enable key.
Nothing currently ships those logs anywhere - they land in whatever captures container stdout
(`podman logs` / `docker logs`), with no aggregation layer behind it.

Representative JSON format (illustrative, **not captured output**):

```json
{
  "timestamp": "2026-07-19T10:30:00.123Z",
  "level": "INFO",
  "loggerName": "com.karyo.inventory.service.StockService",
  "message": "Stock reserved successfully",
  "mdc": {
    "tenantId": "1",
    "userId": "manager",
    "correlationId": "req-abc-123"
  },
  "threadName": "executor-thread-1"
}
```

### MDC keys (verified against `libs/karyo-security/src/main/kotlin/com/karyo/security/TenantFilter.kt`)

`TenantFilter` runs as a `@Provider` JAX-RS `ContainerRequestFilter`. For any authenticated
(non-anonymous) request it populates exactly three MDC keys:

| Key | Source | Notes |
|-----|--------|-------|
| `tenantId` | `tenantContext.clientId` (JWT `client_id` claim) | Numeric, tenancy is silo — one Karyo instance per company |
| `userId` | `tenantContext.username` (JWT `name`) | Falls back to `"unknown"` if the JWT has no name |
| `correlationId` | `X-Correlation-Id` request header | Empty string if the header is absent — **not generated server-side** |

There is no `traceId` or `spanId` MDC key. The old design assumed OpenTelemetry auto-populated
those; since OTel is disabled at runtime, they don't exist.

### Log Levels

| Level | Usage |
|-------|-------|
| ERROR | Unexpected failures requiring investigation |
| WARN | Recoverable issues (retry exhausted, approaching capacity) |
| INFO | Business operations and state changes |
| DEBUG | SQL queries, HTTP details, cache hits/misses |
| TRACE | Fine-grained diagnostics (effectively unused) |

Log level is controlled by standard Quarkus config (`quarkus.log.level`,
`quarkus.log.category."<package>".level`) — no runtime log-level API is exposed.

### Sensitive Data

No redaction filter exists on the **application** log pipeline. Application code is expected not
to log secrets, tokens, or full JWTs; this is a code-review discipline, not an enforced pipeline
stage. Treat this as a known gap rather than a covered control.

The **nginx access log** is the one exception: it runs a redacting `log_format` that replaces the
query string, and the referrer, of any request carrying a credential parameter, because the SPAs'
authorization-code callback travels in the query. The trigger set is deliberately narrow. The
comment above that `log_format` in `infrastructure/docker/nginx/nginx.conf` states which
parameters it covers, and which ordinary query parameters must stay out of it; read it before
changing either side.

---

## 3. Metrics

### What's instrumented

`quarkus-micrometer-registry-prometheus` is a `karyo-app` dependency
(`services/karyo-app/build.gradle.kts`). Quarkus's built-in Micrometer integration exposes
Prometheus-format metrics at `/q/metrics`. Available JVM/HTTP/datasource meters depend on enabled
binders; inspect the endpoint before relying on a particular Hibernate/Panache meter name. **No
custom `karyo_*` business or domain metrics have been implemented** — there is no
`StockMetrics`-style bean in the codebase. Any metric name you might expect (stock operations,
order throughput, pick rate) does not exist yet; treat metric names in older versions of this
document as proposals, not facts.

### What's collected

No scraper is configured in the supported Compose stack. The standard nginx configuration does
not proxy `/q/metrics`; direct backend reachability depends on deployment networking, not that
proxy rule alone. Legacy infrastructure designs are not deployed collection configuration.

### No per-service dimension

Karyo is one process. There is no `service` label distinguishing microservices in any metric —
that concept predates the modular-monolith pivot. Where module-level attribution matters, it has
to come from class/package names in logs or from metric tags added explicitly by future
instrumentation, not from a pre-existing `service` label.

---

## 4. Distributed Tracing

### Current state: instrumented but switched off

`quarkus-opentelemetry` is a `karyo-app` dependency and auto-instruments JAX-RS and
Hibernate/JDBC when active. However, `services/karyo-app/src/main/resources/application.yaml`
explicitly disables it in every profile Karyo actually runs in:

```yaml
'%dev':
  quarkus:
    otel:
      enabled: false

'%prod':
  quarkus:
    otel:
      sdk:
        disabled: true
```

An OTLP exporter endpoint is configured (`quarkus.otel.exporter.otlp.endpoint`, defaulting to
`http://localhost:4317`), but with the SDK disabled that config is inert — there is nothing to
export, and no collector listening on that endpoint in the deployed stack regardless. **No traces
are produced anywhere Karyo currently runs.** Since there is also no Kafka at runtime (CDI events
are synchronous, in-process), there is no cross-boundary propagation problem to solve today — a
single process, single transaction, single thread of execution covers most operations already.

Enabling tracing requires reconciling the build-time `otel.enabled` and runtime SDK settings,
configuring a collector, rebuilding if required and verifying actual spans. It is not proven by
flipping only `otel.sdk.disabled` while build-time instrumentation is disabled.

---

## 5. Health Checks

### Endpoint

SmallRye Health is enabled with `quarkus.smallrye-health.root-path: /q/health`. nginx proxies
**only** `/q/health` (see `infrastructure/docker/nginx/nginx.conf`) — the rest of the `/q/`
management namespace (`/q/metrics`, `/q/dev`, etc.) is deliberately not exposed outside the
Compose network. The admin Health page in the frontend consumes this single proxied endpoint.

There is no Kubernetes here, so the historical startup/liveness/readiness **probe** framing
(`/q/health/started`, `/q/health/live`, `/q/health/ready` wired to K8s probe YAML) doesn't apply
as written — Quarkus still exposes those sub-paths if you call them directly, but nothing in the
deployment consumes them as K8s-style probes; Compose has no equivalent orchestrated
restart-on-probe-failure behavior configured.

### Response shape

Standard SmallRye Health JSON:

```json
{
  "status": "UP",
  "checks": [
    { "name": "Database connections health check", "status": "UP" }
  ]
}
```

Built-in checks come from the extensions already present (datasource, Keycloak/OIDC connectivity
where applicable). No custom `HealthCheck` beans have been added for Karyo-specific readiness
signals (e.g., a Flyway-migrations-applied check) as of this writing.

---

## 6. The Audit Trail as an Observability Surface

`InventoryJournal` records inventory operations and selected authentication events, including
who/what/when and correlation where the writing path supplies it. It is not a guarantee that every
module's state change writes a journal entry. It's exposed via:

- `GET /api/v1/journals?location=<name>` - movement/activity feed matching the source/destination location name
- `GET /api/v1/journals?correlationId=<id>` — every journal row produced by one business
  operation, since `TenantFilter` threads the `X-Correlation-Id` header into MDC and (through
  application code) into journal writes

The admin Audit-log page and the Locations "movements" view are both built directly on this
endpoint. In the absence of distributed tracing, **this is the actual mechanism for reconstructing
a multi-step operation today** — pass the same `X-Correlation-Id` on a related sequence of
requests, then query journals by that id afterward to see the full causal chain.

`outbox_events` is a separate, **active** domain-event log. Webhook fan-out reads it by cursor and
matches subscriptions; delivery sends signed requests with retries. The copilot's recent-activity
tool also reads it. It is neither write-only nor waiting for a Kafka relay. See the
[webhook catalog](../integration/webhook-event-catalog.md) for its external contract. Neither this
log nor the inventory journal is an event-sourced reconstruction of all historical domain state.

---

## 7. Planned / Not Yet Deployed

Everything in this section is aspirational. None of it exists in the running stack. It's kept
here as a plan of record, not a description of current behavior — do not cite anything below as
if it's live.

### Collection stack

| Pillar | Proposed collection | Proposed storage | Proposed visualization |
|--------|---------------------|-------------------|--------------------------|
| Logs | A log shipper reading container stdout | Loki (or similar) | Grafana |
| Metrics | Prometheus scraping `/q/metrics` | Prometheus TSDB | Grafana |
| Traces | Follow the [tracing prerequisites](#4-distributed-tracing) | Jaeger/Tempo | Grafana + Jaeger UI |

Standing this up is Compose-native work (no Kubernetes to inherit collection from): add
Prometheus/Loki/Grafana containers to `infrastructure/docker/docker-compose.prod.yml` or a
sibling monitoring compose file, wire nginx (or a separate internal network) to let Prometheus
reach `/q/metrics`, and pick a log-shipping approach that works against Podman/Docker container
logs rather than a Kubernetes DaemonSet.

### Custom business metrics (none exist yet)

If/when a `StockMetrics`-style bean is built, the naming convention to follow is
`karyo_{domain}_{noun}_total|_seconds|_active` (Counter/Histogram/Gauge respectively), tagged by
business dimensions (`operation`, `tenant`, `order_type`) rather than a `service` label — there is
only one service. Do not add these to production config until the beans actually exist.

### Alerting

No Alertmanager, no PagerDuty/Slack routing, no alert rules exist. `karyo-monitors` (the v1.7a
paid module) is a **separate, already-shipped mechanism** — a scheduled in-app evaluator with 6
built-in detectors that fires `alert.fired` outbox events relayed through the existing webhook
relay. It is not Prometheus/Alertmanager-based and should not be confused with the infra-alerting
stack described here; it's real today, this section is not.

### Dashboards

Any dashboard work (service-level RED panels, business KPI boards, infra panels) is blocked on
the collection stack above existing at all. Nothing here should be built before there's a
Prometheus/Loki instance to point it at.

---

## Related Documents

- [API Standards](api-standards.md)
- [ADR-019: OAuth2/OIDC with Keycloak](decisions/ADR-019-oauth2-oidc-with-keycloak.md)
- [ADR-037: Modular Monolith](decisions/ADR-037-modular-monolith.md)
