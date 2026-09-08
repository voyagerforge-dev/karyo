# ADR-009: API Gateway Pattern (Kong)

> **SUPERSEDED.** Superseded by [ADR-037: Modular Monolith](../ADR-037-modular-monolith.md) (2026-06).
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
Karyo WMS exposes REST APIs from 10+ microservices to three types of clients:

1. **Web dashboard** (React SPA) — warehouse managers viewing dashboards, configuring strategies
2. **Mobile PWA** — warehouse operators scanning, picking, receiving, moving stock
3. **External systems** — ERP integrations, e-commerce webhooks, carrier APIs

Without an API gateway, clients would need to:
- Know the address of each microservice individually
- Handle TLS termination per service
- Implement JWT validation redundantly in each client
- Manage rate limiting per service
- Handle CORS per service

An API gateway provides a single entry point that handles these cross-cutting concerns centrally.

Key requirements:
- **TLS termination:** Single certificate management point
- **JWT validation:** Verify token signature and expiry before forwarding to services
- **Rate limiting:** Per-tenant throttling to prevent noisy-neighbor problems in multi-tenant deployments
- **Routing:** Route `/api/v1/stock-units` to inventory-service, `/api/v1/delivery-orders` to order-service
- **Edge compatible:** Must run on K3s within the edge RAM budget
- **Declarative configuration:** Kubernetes-native configuration via CRDs or Ingress annotations

## Decision
We will use **Kong Gateway (open source)** as the API gateway for all external traffic to Karyo WMS.

### Kong Configuration

**Deployment:** Kong Ingress Controller (KIC) running in Kubernetes, configured via Kubernetes Ingress resources and KongPlugin CRDs.

**Routing:**

| Path Prefix | Target Service | Notes |
|-------------|---------------|-------|
| `/api/v1/auth/*` | auth-service | Login, token refresh |
| `/api/v1/stock-units/*`, `/api/v1/unit-loads/*`, `/api/v1/journals/*` | inventory-service | Stock management |
| `/api/v1/products/*`, `/api/v1/item-units/*` | product-service | Master data |
| `/api/v1/locations/*`, `/api/v1/areas/*`, `/api/v1/zones/*` | warehouse-layout-service | Layout management |
| `/api/v1/delivery-orders/*`, `/api/v1/picking-orders/*`, `/api/v1/shipping-orders/*`, `/api/v1/goods-receipts/*` | order-service | Order management |
| `/api/v1/transport-orders/*`, `/api/v1/replenish-orders/*`, `/api/v1/stocktaking/*` | task-service | Task management |
| `/api/v1/channels/*`, `/api/v1/carriers/*` | integration-hub | External integrations |
| `/api/v1/reports/*`, `/api/v1/dashboards/*` | reporting-service | Analytics |
| `/api/v1/ai/*` | artificial-intelligence-service | AI queries |
| `/api/mobile/v1/*` | mobile-api-gateway | Mobile BFF |

**Plugins:**

| Plugin | Configuration | Purpose |
|--------|--------------|---------|
| JWT | Validate Bearer token signature, expiry, issuer | Authentication |
| Rate Limiting | 100 req/sec per tenant (configurable via KongPlugin) | Throttling |
| CORS | Allow origins for dashboard and mobile PWA domains | Cross-origin |
| Request Transformer | Inject `X-Tenant-Id` header from JWT claims | Tenant context |
| Prometheus | Export request metrics | Monitoring |
| Request Size Limiting | 10 MB max request body | Security |
| IP Restriction | Whitelist for internal APIs (optional) | Security |

**NOT at the Gateway:**
- Business logic — services handle domain validation
- Data transformation beyond header injection — services own their DTOs
- Fine-grained authorization — services check RBAC roles/permissions from JWT claims
- Service-to-service communication — internal traffic bypasses the gateway

### Gateway Resources

| Environment | RAM | CPU |
|-------------|-----|-----|
| Cloud (production) | 256-512 MB | 500m-1000m |
| Edge (K3s) | 64-128 MB | 100m-200m |

Kong's lightweight Nginx-based architecture fits within edge constraints. Kong DB-less mode (declarative config) eliminates the need for a PostgreSQL database for Kong itself at the edge.

## Consequences

### Positive
- **Single entry point:** Clients connect to one URL. Routing complexity is hidden behind the gateway.
- **Centralized security:** TLS termination and JWT validation happen once, not in each service. Services trust that incoming requests have valid tokens because the gateway already validated them.
- **Rate limiting per tenant:** Prevents a single tenant from overwhelming shared services. The 100 req/sec default can be tuned per tenant via KongPlugin annotations.
- **Kubernetes-native:** Kong Ingress Controller uses standard Kubernetes Ingress resources, making routing configuration declarative and version-controlled.
- **Edge compatible:** Kong DB-less mode runs at 64-128 MB RAM, fitting within the edge budget. Declarative configuration (YAML) eliminates the need for a separate Kong database.
- **Observability:** Kong Prometheus plugin exports request metrics (latency, status codes, per-route) without custom instrumentation in services.
- **OpenAPI aggregation:** Kong can serve a unified OpenAPI spec aggregating all service specs, providing a single developer portal for API consumers.

### Negative
- **Single point of failure:** The gateway is on the critical path for all external traffic. Mitigated by running 2+ Kong replicas with pod anti-affinity.
- **Added latency:** Each request passes through Kong before reaching the service. Kong's Nginx-based architecture adds < 1ms per request, which is negligible for the 200ms p95 target.
- **Configuration complexity:** Kong plugin configuration, route management, and consumer management add operational overhead. Mitigated by declarative configuration in Git (GitOps).
- **Not for internal traffic:** Service-to-service calls bypass Kong (direct Kubernetes DNS), so internal API security (mTLS, service accounts) must be managed separately.

### Neutral
- The gateway does not reduce the number of REST calls — it routes them. The mobile-api-gateway (BFF) handles call aggregation for mobile clients.
- Kong supports both DB-backed and DB-less modes. Edge uses DB-less; cloud can use either.

## Alternatives Considered

### Alternative 1: Envoy / Istio Service Mesh
- **Pros**: Full service mesh capabilities (mTLS between all services, traffic management, observability), L7 routing, powerful traffic control (canary, fault injection, mirroring).
- **Cons**: Istio control plane consumes 500MB+ RAM (exceeds edge budget), sidecar proxy per pod adds 50-100 MB per service (multiplied by 6+ services at edge = 300-600 MB), significant operational complexity, steep learning curve, overkill for the current scale (< 10 services).
- **Why rejected**: Istio's resource overhead (control plane + sidecars) would consume 800MB+ at the edge, leaving insufficient RAM for services. For < 10 services, the full service mesh is over-engineered. mTLS can be handled by Kubernetes certificate management or a lighter approach. Envoy as a standalone gateway (without Istio) is viable but has a less user-friendly configuration model than Kong.

### Alternative 2: Spring Cloud Gateway
- **Pros**: Java-native, Spring ecosystem integration, reactive routing, programmatic route configuration.
- **Cons**: Java-only (adds a JVM to the gateway, 128-256 MB), slower than Nginx-based gateways, tightly coupled to Spring ecosystem (Karyo uses Quarkus), no Kubernetes CRD integration (requires custom configuration).
- **Why rejected**: Adding a JVM-based gateway on top of JVM-based services increases memory consumption unnecessarily. Kong's Nginx-based architecture is more efficient. Spring Cloud Gateway's Spring ecosystem coupling conflicts with Karyo's Quarkus-based architecture.

### Alternative 3: KrakenD
- **Pros**: Stateless, high performance, declarative JSON configuration, Go-based (lightweight).
- **Cons**: Smaller community than Kong, less mature plugin ecosystem, no native Kubernetes Ingress Controller (requires custom integration), fewer production references, commercial features gate some observability capabilities.
- **Why rejected**: KrakenD's smaller ecosystem and lack of a native Kubernetes Ingress Controller add integration overhead. Kong's larger community, extensive plugin ecosystem, and KIC provide a lower-risk choice for production deployment.

### Alternative 4: Custom Nginx Configuration
- **Pros**: Maximum control, lowest overhead, no abstraction layer.
- **Cons**: Manual configuration management (no CRDs), JWT validation requires Lua scripting or nginx-jwt module, rate limiting requires custom modules, no built-in Prometheus metrics, high maintenance burden as routing grows.
- **Why rejected**: Managing raw Nginx configuration for 10+ service routes, JWT validation, rate limiting, and CORS becomes a significant maintenance burden. Kong provides these capabilities as plugins while using Nginx under the hood for performance.

## Implementation Notes
- **Deployment:** Kong Ingress Controller via Helm chart in the `karyo-prod` namespace (or `karyo-infra`).
- **DB-less mode (edge):** Use `KONG_DATABASE=off` with declarative configuration stored in a ConfigMap.
- **DB mode (cloud):** Optional — use PostgreSQL for Kong if dynamic plugin configuration is needed. DB-less with GitOps is preferred for simplicity.
- **TLS:** cert-manager with Let's Encrypt for automatic certificate provisioning and renewal.
- **Rate limiting keys:** Extract `tenantId` from JWT claims and use as rate limiting key. Configure via `KongPlugin` CRD per service route.
- **Health check endpoint:** Kong health check at `/status` — separate from service health checks.
- **Monitoring:** Kong Prometheus plugin exports `kong_http_requests_total`, `kong_http_request_duration_ms`, `kong_bandwidth_bytes` metrics.

## Related Decisions
- [ADR-001: Microservices Architecture Style](ADR-001-microservices-architecture.md)
- [ADR-007: Kubernetes as Orchestration Platform](ADR-007-kubernetes-orchestration.md)
- [ADR-008: REST for Synchronous, Events for Asynchronous](../ADR-008-rest-sync-events-async.md)

## References
- Kong Gateway: https://konghq.com/kong/
- Kong Ingress Controller: https://docs.konghq.com/kubernetes-ingress-controller/
- Kong DB-less mode: https://docs.konghq.com/gateway/latest/production/deployment-topologies/db-less-and-declarative-config/
- Kong Plugins: https://docs.konghq.com/hub/

## Revision History
- 2026-02-15: Initial version
