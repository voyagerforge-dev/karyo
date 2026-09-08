# ADR-019: OAuth2/OIDC with Keycloak

> **PARTIALLY SUPERSEDED** for current practice by the current
> [production deployment contract](../../../DEPLOY.md). Still in force: Keycloak as the OIDC
> identity provider,
> Authorization Code + PKCE for browsers, JWT validation in `karyo-app`.
>
> Not the deployed model: optional/standalone Keycloak, a `karyo-mobile` client, or `karyo-admin`
> as a frontend application. Production uses three Keycloak clients (`karyo-web`, `karyo-backend`,
> `karyo-admin` as a least-privilege user-administration service account) and has no non-Keycloak
> auth mode.

## Status
Partially superseded

> **Current runtime:** Keycloak is mandatory. Browser login uses the public `karyo-web` client
> for both the desktop console and the floor PWA. `karyo-admin` and `karyo-backend` are
> confidential service-account clients, not frontend applications. See [DEPLOY.md](../../../DEPLOY.md).

## Context
Karyo WMS must support multiple user types (warehouse operators, managers, administrators, integrators) across multiple deployment models (full cloud, full on-premise, hybrid edge+cloud). The authentication and authorization system must handle:

- **Multi-tenant isolation**: Operators in one tenant must never access another tenant's data
- **Role-based access control (RBAC)**: Fine-grained permissions per service (e.g., `inventory-read`, `inventory-write`, `orders-manage`)
- **Mobile PWA authentication**: Warehouse operators on mobile devices/scanners need secure, frictionless authentication
- **Service-to-service authentication**: Microservices must authenticate each other for internal API calls
- **Federation**: Enterprise customers may require SSO with their corporate identity providers (Active Directory, SAML, etc.)
- **Offline capability**: Edge deployments must continue authenticating users during cloud disconnection
- **Regulatory compliance**: FDA 21 CFR Part 11 requires audit trails for user authentication events

The legacy myWMS system uses a simple `User` + `Role` model in `mywms.as-ejb` with session-based authentication. Karyo must modernize this to industry-standard token-based authentication suitable for a microservices architecture.

## Decision
We will use **OAuth2/OIDC** as the authentication and authorization protocol, with **Keycloak 24+** as the primary (but optional) identity provider.

**Architecture:**

```
┌─────────────┐     ┌──────────────┐     ┌─────────────────┐
│  Mobile PWA │────►│  API Gateway  │────►│  Microservices  │
│  (PKCE)     │     │ (token check) │     │ (JWT validate)  │
└─────────────┘     └──────┬───────┘     └─────────────────┘
                           │
┌─────────────┐     ┌──────▼───────┐
│  Web UI     │────►│   Keycloak   │
│  (Auth Code)│     │  (IdP)       │
└─────────────┘     └──────────────┘
                           │
┌─────────────┐            │
│ External    │────────────┘
│ IdP (SAML)  │  ← Federation / Identity Brokering
└─────────────┘
```

**OAuth2 Flows:**

| Client Type | OAuth2 Flow | Rationale |
|------------|-------------|-----------|
| Web UI (React SPA) | Authorization Code with PKCE | Standard for SPAs, no client secret exposure |
| Mobile PWA | Authorization Code with PKCE | Same as web, works on mobile browsers and scanner devices |
| Service-to-service | Client Credentials | Machine-to-machine, no user context needed |
| External integrations | Client Credentials or API Key + JWT exchange | Third-party system access |

**JWT Token Structure:**

```json
{
  "iss": "https://auth.karyo.dev/realms/karyo",
  "sub": "user-uuid-here",
  "aud": ["karyo-api"],
  "exp": 1709510400,
  "iat": 1709506800,
  "tenantId": 42,
  "warehouseId": 7,
  "roles": ["OPERATOR", "RECEIVER"],
  "permissions": ["inventory:read", "orders:read", "orders:pick", "receiving:write"],
  "preferred_username": "jdoe",
  "name": "John Doe"
}
```

**Token Configuration:**
- Access token TTL: 1 hour (short-lived, reduces risk of token theft)
- Refresh token TTL: 7 days (operators on shift-based schedules)
- Token refresh: silent refresh via refresh token rotation (one-time use refresh tokens)
- Token storage: in-memory (access token), secure HTTP-only cookie (refresh token)

**Keycloak Configuration:**
- One Keycloak realm: `karyo`
- Tenant isolation via Keycloak groups or custom user attributes (`tenantId`, `warehouseId`)
- Custom protocol mapper for `tenantId`, `warehouseId`, and `permissions` claims
- Client per frontend application: `karyo-web`, `karyo-mobile`, `karyo-admin`
- Service account per microservice: `inventory-service-sa`, `order-service-sa`, etc.

**Keycloak is OPTIONAL:**
The auth-service can operate in standalone mode with a local user database for deployments where Keycloak is not desired (e.g., small on-premise installations, development environments). In standalone mode:
- auth-service issues JWT tokens directly using its own signing keys
- User management is handled through auth-service REST API
- RBAC is managed through auth-service's internal role/permission model
- No federation or SSO capability in standalone mode

**RBAC Model (from myWMS, extended):**

| Role | Scope | Permissions |
|------|-------|------------|
| ADMIN | System-wide | All operations, tenant management, system configuration |
| MANAGER | Per-tenant | Reports, analytics, configuration, user management within tenant |
| OPERATOR | Per-tenant | Picking, packing, shipping, stock queries |
| RECEIVER | Per-tenant | Goods receipt, putaway operations |
| VIEWER | Per-tenant | Read-only access to dashboards and reports |
| INTEGRATOR | Per-tenant | API access for external system integration |
| AI_SERVICE | System-wide | AI service account (limited to AI-related endpoints) |

## Consequences

### Positive
- Industry-standard authentication protocol (OAuth2/OIDC) — well understood, widely supported, extensive library ecosystem
- Keycloak provides enterprise features out of the box: SSO, federation (SAML, LDAP, Active Directory), MFA, brute-force protection, audit logging
- JWT tokens are self-contained — microservices can validate tokens locally without calling the identity provider on every request (after fetching the JWKS public key)
- Token-based authentication works naturally with the API Gateway pattern and microservices architecture
- PKCE flow is the recommended standard for SPAs and mobile apps, eliminating the need for client secrets in frontends
- Federation support enables enterprise customers to use their existing identity infrastructure
- Keycloak's admin console reduces the need for custom user management UI

### Negative
- Keycloak adds an infrastructure component to deploy and manage (Java-based, requires its own database)
- Keycloak's resource footprint (~512MB-1GB RAM) may be significant for edge deployments
- JWT token size increases with custom claims — each request carries the full token (~1-2KB)
- Token revocation is not instant — a revoked token remains valid until expiration (mitigated by short TTL)
- Keycloak version upgrades require careful testing of realm configuration compatibility
- Dual-mode (Keycloak vs. standalone) requires maintaining two authentication code paths in auth-service

### Neutral
- Quarkus has excellent OIDC integration via `quarkus-oidc` extension — minimal configuration needed for JWT validation in each microservice
- Keycloak's admin UI may or may not be exposed to end customers depending on deployment model
- The RBAC model can be extended with fine-grained attribute-based access control (ABAC) in the future if needed

## Alternatives Considered

### Alternative 1: Auth0
- **Pros**: Fully managed SaaS, excellent developer experience, rich documentation, built-in MFA, anomaly detection, universal login
- **Cons**: SaaS-only — cannot run on-premise or at the edge, per-MAU pricing becomes expensive for large warehouse operations (hundreds of operators), vendor lock-in, data residency concerns for EU customers (GDPR)
- **Why rejected**: Incompatible with on-premise and edge deployment requirements. Cost per monthly active user is prohibitive when a single warehouse may have 50-200 operators. No self-hosted option.

### Alternative 2: Custom JWT Implementation
- **Pros**: Full control over token format and authentication flow, no external dependency, lightest resource footprint, simplest deployment
- **Cons**: Security risk — building a secure authentication system from scratch requires deep security expertise and ongoing maintenance. No built-in federation, MFA, brute-force protection, or audit logging. Every security feature must be implemented and tested manually. High development cost.
- **Why rejected**: The security risk of building a custom authentication system outweighs the simplicity benefits. Authentication is a solved problem; using a proven identity provider is the industry best practice.

### Alternative 3: Firebase Authentication
- **Pros**: Free for up to 10K MAU, simple integration, supports social logins, managed by Google
- **Cons**: Google Cloud dependency, limited customization (no custom claims beyond basic), no on-premise option, no SAML federation for enterprise SSO, limited audit logging for compliance
- **Why rejected**: Google Cloud lock-in contradicts multi-cloud and on-premise deployment strategy. Insufficient customization for warehouse-specific claims (tenantId, warehouseId, warehouse-specific permissions). No enterprise federation support.

## Implementation Notes
- Deploy Keycloak as a Kubernetes StatefulSet with PostgreSQL backend (can share the same PostgreSQL cluster as services, separate database `karyo_keycloak`)
- For edge deployments, consider Keycloak in embedded mode or using the standalone auth-service mode to reduce resource footprint
- Implement a Quarkus CDI interceptor that extracts `tenantId` from the JWT and sets it in the request context for use by all downstream service calls and database queries (ties into ADR-020 multi-tenancy)
- Configure Quarkus `quarkus-oidc` in each microservice to validate JWTs against Keycloak's JWKS endpoint (cached with configurable TTL)
- For offline/edge scenarios: cache the JWKS public key locally; tokens can be validated without network access to Keycloak as long as the cached key is valid
- Implement token refresh handling in the React frontend using a silent refresh mechanism (background iframe or service worker)
- Audit log all authentication events (login, logout, token refresh, failed attempts) in auth-service for regulatory compliance
- Create a Keycloak realm export/import script for reproducible environment setup across dev, staging, and production

## Related Decisions
- [ADR-020](ADR-020-multi-tenancy-strategy.md): Multi-Tenancy Strategy — tenantId in JWT tokens drives row-level data isolation
- [ADR-021](superseded/ADR-021-mtls-for-service-communication.md): mTLS for Service-to-Service — complements JWT with transport-level security
- [ADR-027](ADR-027-pwa-for-mobile.md): PWA for Mobile — mobile authentication uses PKCE flow defined here

## References
- [OAuth 2.0 for Browser-Based Applications (RFC 8252)](https://datatracker.ietf.org/doc/html/rfc8252)
- [Proof Key for Code Exchange (PKCE, RFC 7636)](https://datatracker.ietf.org/doc/html/rfc7636)
- [Keycloak Documentation](https://www.keycloak.org/documentation)
- [Quarkus OIDC Extension Guide](https://quarkus.io/guides/security-oidc-bearer-token-authentication)
- [FDA 21 CFR Part 11 Electronic Records Requirements](https://www.ecfr.gov/current/title-21/chapter-I/subchapter-A/part-11)

## Revision History
- 2026-02-15: Initial version
