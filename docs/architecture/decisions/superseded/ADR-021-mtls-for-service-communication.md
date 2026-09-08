# ADR-021: mTLS for Service-to-Service Communication

> **SUPERSEDED.** Superseded by [ADR-037: Modular Monolith](../ADR-037-modular-monolith.md) (2026-06).
>
> Retained as decision history: it records why the boundaries in the current modular
> monolith are drawn where they are. Do not treat anything below as current.

## Status
Superseded

## Context
Karyo WMS's microservices architecture involves frequent inter-service communication: order-service queries inventory-service for stock availability, task-service calls warehouse-layout-service for location finding, and so on. These internal API calls carry sensitive warehouse operational data including inventory levels, order details, customer information, and tenant-specific configurations.

The security requirements for inter-service communication vary by deployment environment:
- **Development**: Fast iteration, minimal friction, services run on localhost
- **Staging**: Production-like security with debugging capabilities
- **Production (cloud)**: Full zero-trust security, defense against lateral movement attacks
- **Production (edge)**: Security with minimal resource overhead on constrained hardware

The system must protect against:
- Network-level eavesdropping on inter-service traffic
- Unauthorized service impersonation (rogue service calling internal APIs)
- Lateral movement by an attacker who compromises one service
- Man-in-the-middle attacks on service-to-service calls

## Decision
We will implement a **layered security model** for service-to-service communication, with mTLS as the production standard:

| Environment | Transport Security | Authentication | Rationale |
|------------|-------------------|----------------|-----------|
| **Development** | None (HTTP) | None or JWT service account | Quarkus Dev Services on localhost, zero friction |
| **Staging** | TLS (one-way) | JWT service accounts | Simpler debugging, production-like auth |
| **Production (cloud)** | mTLS via service mesh | mTLS + JWT service accounts | Zero-trust, defense-in-depth |
| **Production (edge)** | mTLS via service mesh (Linkerd) or self-managed | mTLS + JWT service accounts | Same security as cloud, lighter mesh |

**Service Mesh for mTLS (Production):**

```
┌──────────────┐          mTLS           ┌──────────────┐
│ order-service │◄──────────────────────►│  inventory-   │
│               │                        │   service     │
│  ┌─────────┐  │                        │  ┌─────────┐  │
│  │ Envoy/  │  │   Encrypted +          │  │ Envoy/  │  │
│  │ linkerd │  │   Mutually             │  │ linkerd │  │
│  │ proxy   │  │   Authenticated        │  │ proxy   │  │
│  └─────────┘  │                        │  └─────────┘  │
└──────────────┘                        └──────────────┘
       ▲                                       ▲
       │        Certificate Authority          │
       └────────── (cert-manager) ─────────────┘
```

**Certificate Management:**
- cert-manager with an internal CA (self-signed root or corporate PKI) for mTLS certificates
- Automatic certificate rotation (24-hour TTL, renewed at 50% lifetime)
- Each service gets a unique identity certificate (SPIFFE ID format: `spiffe://karyo.local/{service-name}`)
- No manual certificate management — fully automated by the service mesh

**JWT Service Accounts (Application-Level, All Environments):**

Even with mTLS, each service uses a JWT service account for application-level authorization:

```kotlin
// Each service has a Keycloak service account with specific permissions
// inventory-service-sa can only call endpoints it's authorized for
// This provides defense-in-depth: mTLS ensures the caller IS inventory-service,
// JWT ensures inventory-service is ALLOWED to call this endpoint

@Path("/api/internal/stock-selection")
@RolesAllowed("service:inventory-service")  // Only inventory-service can call this
class StockSelectionInternalResource(
    private val stockSelectionService: StockSelectionService,
) {
    @GET
    fun findSourceStock(
        @QueryParam("itemDataId") itemDataId: Long,
        @QueryParam("tenantId") tenantId: Long,
        @QueryParam("strategyId") strategyId: Long,
    ): StockSelectionResponse { ... }
}
```

**Internal vs. External API Separation:**
- Internal APIs: `/api/internal/*` — only accessible from within the service mesh (network policy + mTLS)
- External APIs: `/api/v1/*` — accessible from API gateway with user JWT tokens
- Kubernetes NetworkPolicy restricts `/api/internal/*` endpoints to cluster-internal traffic only

**Service Mesh Selection:**
- Primary recommendation: **Istio** for cloud deployments (most features, largest ecosystem)
- Alternative: **Linkerd** for edge deployments (lighter footprint, simpler operation, ~50MB per proxy vs ~150MB for Envoy)

## Consequences

### Positive
- Zero-trust security model — every service-to-service call is encrypted and mutually authenticated, even within the cluster
- Automatic certificate rotation eliminates manual certificate management and reduces the risk of expired certificates
- Defense-in-depth: mTLS (transport) + JWT (application) + NetworkPolicy (network) provides three layers of security
- Service mesh provides additional benefits: traffic management, observability, retries, circuit breaking
- Compliant with security requirements for regulated industries (FDA, SOX, PCI-DSS)
- Per-environment configuration allows development speed without compromising production security

### Negative
- Service mesh adds infrastructure complexity and resource overhead (~50-150MB RAM per sidecar proxy per pod)
- mTLS adds latency to every inter-service call (~1-5ms for TLS handshake, amortized with connection pooling)
- Debugging encrypted traffic is harder — requires service mesh observability tools (Kiali for Istio, Linkerd dashboard)
- Service mesh operational overhead: version upgrades, configuration management, troubleshooting mesh issues
- Edge deployments with many services may not have budget for sidecar proxies (mitigated by using Linkerd or ambient mesh mode)

### Neutral
- Istio's ambient mesh mode (ztunnel) is an emerging option that eliminates sidecar proxies; may be evaluated for edge deployments when stable
- The JWT service account layer works independently of mTLS and can be the sole authentication mechanism in environments without a service mesh
- gRPC calls (used for high-throughput internal APIs like stock selection) benefit from TLS session reuse in the service mesh

## Alternatives Considered

### Alternative 1: JWT Only (No Transport Encryption Between Services)
- **Pros**: Simplest implementation, no service mesh needed, no certificate management, lowest latency, works identically across all environments
- **Cons**: No transport encryption — an attacker on the cluster network can read inter-service traffic in plaintext. Token theft via network sniffing enables impersonation. Does not meet zero-trust requirements. Fails compliance audits for regulated industries.
- **Why rejected**: Insufficient security for a warehouse management system that handles sensitive business data across multiple tenants. Network-level encryption is a baseline security requirement in production.

### Alternative 2: API Keys
- **Pros**: Simple to implement, no external identity provider needed, lightweight
- **Cons**: Static secrets that must be rotated manually, no built-in expiration, difficult to scope permissions per endpoint, no standard for claims or authorization, key leakage is hard to detect and remediate
- **Why rejected**: API keys provide weaker security than JWT tokens and lack the authorization capabilities (claims, roles, permissions) needed for fine-grained service-to-service access control.

### Alternative 3: VPN / Network-Level Encryption
- **Pros**: Encrypts all traffic within the VPN, transparent to applications, no code changes needed
- **Cons**: Network-level security, not service-level — any service within the VPN can call any other service (no mutual authentication). Coarse-grained access control. VPN adds network latency and complexity. Does not work well with Kubernetes service discovery.
- **Why rejected**: VPN provides encryption but not authentication or authorization. A compromised service within the VPN can impersonate any other service. The zero-trust model requires service-level identity verification, which VPN does not provide.

## Implementation Notes
- Start without a service mesh in development (Phase 0-1); introduce Istio/Linkerd in staging when multiple services are deployed (Phase 2+)
- Use Quarkus `quarkus-oidc-client` extension for automatic service account token acquisition and refresh in service-to-service REST clients
- Configure Kubernetes NetworkPolicy to restrict `/api/internal/*` endpoints to cluster-internal sources only
- For edge deployments, evaluate Linkerd (lighter) vs. Istio ambient mesh vs. application-level TLS (no mesh) based on resource constraints
- Implement a `ServiceAuthFilter` that validates both mTLS identity (from service mesh headers like `X-Forwarded-Client-Cert`) and JWT service account tokens
- Log all inter-service authentication failures as security events to the observability stack (ADR-023, ADR-025)
- In CI/CD, run integration tests both with and without mTLS to ensure services function correctly in all deployment modes

## Related Decisions
- [ADR-019](../ADR-019-oauth2-oidc-with-keycloak.md): OAuth2/OIDC with Keycloak — provides JWT service accounts used alongside mTLS
- [ADR-024](../ADR-024-opentelemetry-distributed-tracing.md): OpenTelemetry — service mesh provides additional observability data for distributed traces
- [ADR-022](ADR-022-gitops-with-argocd.md): GitOps with ArgoCD — service mesh configuration managed declaratively via GitOps

## References
- [Istio Security Architecture](https://istio.io/latest/docs/concepts/security/)
- [Linkerd mTLS Documentation](https://linkerd.io/2/features/automatic-mtls/)
- [SPIFFE: Secure Production Identity Framework for Everyone](https://spiffe.io/)
- [cert-manager Documentation](https://cert-manager.io/docs/)
- [Kubernetes Network Policies](https://kubernetes.io/docs/concepts/services-networking/network-policies/)

## Revision History
- 2026-02-15: Initial version
