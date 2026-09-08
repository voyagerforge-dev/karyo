# ADR-029: Quarkus Dev Services for Local Development

> **PARTIALLY SUPERSEDED** by [ADR-037: Modular Monolith](ADR-037-modular-monolith.md) — Still used; the dependency list shrank.
>
> Dev Services remain supported. The intended Compose services are PostgreSQL and Keycloak,
> not Kafka or Redis. Automatic discovery must be verified: a healthy backend can start using
> a database Dev Service while Keycloak is absent. Check OIDC discovery/login separately and use
> the [development guide](../../guides/developer-onboarding.md) for setup. Tests use Testcontainers.


## Status
Partially superseded

## Context
Karyo WMS's microservices architecture requires multiple infrastructure dependencies for local development:

- **PostgreSQL**: Each service's database (9 databases in full local development)
- **Apache Kafka**: Event backbone for inter-service communication
- **Redis**: Distributed cache and session storage
- **Keycloak**: Identity provider (optional for local dev)

Setting up and maintaining these dependencies manually is error-prone and time-consuming. Developers face common issues:
- Version drift between local installations and production
- Port conflicts between developers running different projects
- Different setup procedures for macOS, Linux, and Windows
- Outdated local databases missing migration changes
- "Works on my machine" problems due to configuration differences

The development experience must support:
- **Zero-configuration startup**: A new developer should be productive within minutes of cloning the repo
- **Live reload**: Code changes reflected instantly without restart
- **Continuous testing**: Tests run automatically on code changes during development
- **Parity with production**: Local dependencies should match production versions

## Decision
We will use **Quarkus Dev Services** as the primary local development environment, with Testcontainers providing auto-provisioned infrastructure dependencies.

**How It Works:**

```
Developer runs: ./gradlew :services:inventory-service:quarkusDev
                         │
                         ▼
                ┌─────────────────┐
                │  Quarkus Dev    │
                │  Mode Launcher  │
                │                 │
                │  Detects no DB  │──► Starts PostgreSQL Testcontainer
                │  Detects no     │──► Starts Kafka Testcontainer
                │  Kafka          │──► Starts Redis Testcontainer
                │  Detects no     │
                │  Redis          │
                └────────┬────────┘
                         │
                         ▼
                ┌─────────────────┐
                │  Application    │
                │  Starts with    │
                │  auto-config    │
                │                 │
                │  Port: 8080     │
                │  Dev UI: 8080/q │
                └─────────────────┘
```

**Quarkus Dev Mode Features Used:**

| Feature | Description | Benefit |
|---------|------------|---------|
| **Dev Services** | Auto-provision PostgreSQL, Kafka, Redis via Testcontainers | Zero configuration |
| **Live Reload** | Hot-swap code changes without restart | Instant feedback loop |
| **Continuous Testing** | Tests run automatically on file save | Immediate regression detection |
| **Dev UI** | Web dashboard for inspecting CDI, Kafka, DB, OpenAPI | Debug and explore at `localhost:8080/q/dev-ui` |
| **Configuration Overrides** | Dev profile auto-applied | Dev-specific settings without env vars |

**Configuration (`application.properties`):**

```properties
# Default (dev profile - auto-applied in dev mode)
%dev.quarkus.datasource.devservices.enabled=true
%dev.quarkus.datasource.devservices.image-name=postgres:16-alpine
%dev.quarkus.datasource.devservices.port=0                # Random available port
%dev.quarkus.datasource.devservices.db-name=karyo_inventory_dev

%dev.quarkus.kafka.devservices.enabled=true
%dev.quarkus.kafka.devservices.image-name=confluentinc/cp-kafka:7.6.0
%dev.quarkus.kafka.devservices.port=0

%dev.quarkus.redis.devservices.enabled=true
%dev.quarkus.redis.devservices.image-name=redis:7-alpine

# Flyway runs automatically on dev service startup
%dev.quarkus.flyway.migrate-at-start=true

# Production config (uses real infrastructure)
%prod.quarkus.datasource.jdbc.url=jdbc:postgresql://${DB_HOST}:5432/karyo_inventory
%prod.quarkus.datasource.username=${DB_USERNAME}
%prod.quarkus.datasource.password=${DB_PASSWORD}
%prod.quarkus.datasource.devservices.enabled=false
```

Current logging defaults are owned by the
[observability architecture](../observability-architecture.md#2-structured-logging), not this
historical multi-service configuration sketch.

**Shared Dev Services (Multiple Services):**

When running multiple Karyo services simultaneously in dev mode, Testcontainers can share infrastructure:

```properties
# All services share the same Kafka dev service
%dev.quarkus.kafka.devservices.service-name=karyo-kafka
%dev.quarkus.kafka.devservices.shared=true

# Each service gets its own database (different db-name, same PostgreSQL instance)
%dev.quarkus.datasource.devservices.service-name=karyo-postgres
%dev.quarkus.datasource.devservices.shared=true
```

**Test Configuration (Same Testcontainers):**

```properties
# application-test.properties
%test.quarkus.datasource.devservices.enabled=true
%test.quarkus.datasource.devservices.image-name=postgres:16-alpine
%test.quarkus.kafka.devservices.enabled=true
%test.quarkus.flyway.migrate-at-start=true
%test.quarkus.flyway.clean-at-start=true    # Clean DB before each test suite
```

```kotlin
// Integration test uses the same Testcontainers infrastructure
@QuarkusTest
class StockServiceIntegrationTest {

    @Inject
    lateinit var stockService: StockService

    @Test
    fun `should reserve stock and persist to database`() {
        // PostgreSQL Testcontainer is already running
        // Flyway migrations already applied
        // Test data can be inserted directly

        val result = stockService.reserveStock(stockUnitId = 1L, amount = BigDecimal("10"))
        assertThat(result).isInstanceOf(PickResult.Success::class.java)
    }
}
```

**Dev UI Capabilities:**

The Quarkus Dev UI (accessible at `http://localhost:8080/q/dev-ui`) provides:

| Panel | Capability |
|-------|-----------|
| **CDI** | Browse all CDI beans, their scopes, qualifiers, and interceptors |
| **OpenAPI / Swagger UI** | Interactive API testing with generated documentation |
| **Database** | SQL console connected to the dev PostgreSQL instance |
| **Kafka** | Browse topics, produce/consume test messages |
| **Flyway** | View applied migrations, migration status |
| **Health** | Liveness/readiness health check status |
| **Configuration** | View all configuration properties and their sources |
| **Continuous Testing** | Run/pause tests, view results, filter by tag |

## Consequences

### Positive
- Zero-configuration local development — new developers run `./gradlew quarkusDev` and have a working service with all dependencies within 30-60 seconds (first run) or 10-15 seconds (cached containers)
- Testcontainers ensure local infrastructure versions match production — PostgreSQL 16, Kafka 7.x, Redis 7 — eliminating version drift
- Same Testcontainers infrastructure used in both dev mode and automated tests — no separate test database configuration
- Live reload provides sub-second feedback for code changes (Quarkus recompiles and reloads only changed classes)
- Continuous testing catches regressions immediately during development without manual test execution
- Dev UI reduces context switching — developers inspect database state, Kafka topics, and API specs without leaving the browser
- No Docker Compose files to maintain per-service — Quarkus manages container lifecycle automatically

### Negative
- Requires Docker (or Podman) running on the developer machine — adds a prerequisite to the development environment setup
- Testcontainer startup adds 10-30 seconds to initial dev mode launch (subsequent launches reuse running containers)
- Container resource consumption: PostgreSQL + Kafka + Redis containers consume ~1-2GB RAM in aggregate, which may be significant on developer machines with limited memory
- Dev Services magic — auto-configuration can be confusing when debugging connectivity issues (which port is the database on?)
- When running multiple services simultaneously, shared Testcontainers can have race conditions during startup

### Neutral
- Docker Compose remains available as a fallback for developers who prefer explicit infrastructure control or need custom configurations
- Podman is supported as a Docker alternative, though some Testcontainers features may behave differently
- Dev Services can be disabled per-service (`devservices.enabled=false`) if a developer prefers to use a locally installed PostgreSQL or an external shared database

## Alternatives Considered

### Alternative 1: Docker Compose
- **Pros**: Explicit infrastructure definition, works with any framework (not Quarkus-specific), full control over networking and volumes, well-understood by most developers
- **Cons**: Manual setup — developers must run `docker-compose up` separately before starting the application. Version drift between Docker Compose file and production Helm charts. Port conflicts require manual resolution. Separate configuration management from application config. Docker Compose file must be maintained per-service or as a monolithic file for all services.
- **Why rejected**: Docker Compose adds manual steps to the development workflow and requires maintaining a separate infrastructure-as-code file that can drift from production. Quarkus Dev Services provides the same outcome (containerized dependencies) with zero manual setup and automatic lifecycle management.

### Alternative 2: Local Installations (brew install postgresql, etc.)
- **Pros**: No container overhead, fastest possible startup, no Docker dependency
- **Cons**: Version management is manual (each developer may have different PostgreSQL versions), installation procedures differ across macOS/Linux/Windows, port conflicts with other projects, no automatic cleanup, "works on my machine" is endemic, no isolation between projects
- **Why rejected**: The inconsistency risk across developer machines outweighs the performance benefit. The team would spend more time debugging environment differences than the time saved by avoiding containers.

### Alternative 3: Vagrant (Virtual Machine)
- **Pros**: Complete environment isolation, reproducible via Vagrantfile, includes OS-level configuration
- **Cons**: Heavy resource overhead (full VM vs. container), slow startup (minutes vs. seconds), large disk footprint, outdated approach for modern cloud-native development, poor IDE integration (remote development adds latency)
- **Why rejected**: Virtual machines are disproportionately heavy for provisioning database and messaging dependencies. Containers provide equivalent isolation with a fraction of the resource overhead.

## Implementation Notes
- Ensure Docker or Podman is listed as a prerequisite in the repository README and developer setup guide
- Configure Quarkus Dev Services in `application.properties` with explicit image tags matching production versions (e.g., `postgres:16-alpine`, not `postgres:latest`)
- Use `%dev.` profile prefix for all dev-specific configuration; use `%test.` for test-specific overrides
- Enable shared Dev Services (`shared=true`) with common `service-name` values so that running multiple services locally reuses the same PostgreSQL and Kafka containers
- Create a `Makefile` or script with convenience targets:
  - `make dev-inventory` → `./gradlew :services:inventory-service:quarkusDev`
  - `make dev-all` → starts all services (or a core subset) in parallel
  - `make test` → `./gradlew test`
- For Kafka Dev Services, configure initial topic creation via `%dev.quarkus.kafka.devservices.topic-partitions.karyo.inventory.events=3`
- Document the Dev UI URL (`http://localhost:8080/q/dev-ui`) in the developer onboarding guide
- For CI/CD (GitHub Actions), Dev Services work out of the box since GitHub Actions runners have Docker installed; use `testcontainers.reuse.enable=true` in CI for faster test suites

## Related Decisions
- [ADR-002](ADR-002-quarkus-framework.md): Quarkus as Microservices Framework — Dev Services is a Quarkus-specific feature that influenced the framework choice
- [ADR-028](ADR-028-gradle-kotlin-dsl-build-tool.md): Gradle — Quarkus Dev Mode is invoked via the Gradle plugin
- [ADR-004](superseded/ADR-004-database-per-service.md): Database-per-Service Pattern — each service's Dev Services creates its own database

## References
- [Quarkus Dev Services Documentation](https://quarkus.io/guides/dev-services)
- [Quarkus Dev Mode Guide](https://quarkus.io/guides/maven-tooling#dev-mode)
- [Quarkus Continuous Testing](https://quarkus.io/guides/continuous-testing)
- [Testcontainers Documentation](https://testcontainers.com/)
- [Quarkus Dev UI Guide](https://quarkus.io/guides/dev-ui)

## Revision History
- 2026-02-15: Initial version
