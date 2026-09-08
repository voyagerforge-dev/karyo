# ADR-002: Quarkus as Microservices Framework

## Status
Accepted

**Current implementation:** Platform and test-artifact selection is owned by the
[version catalog](../../../gradle/libs.versions.toml). The versioned examples and JUnit 5
reference below record the original decision, not current dependency requirements.
See the [ADR index](README.md#adr-index) for the retained framework decision's disposition.

## Context
Karyo WMS requires a Java/Kotlin microservices framework that meets several demanding constraints:

1. **Edge deployment:** Core services (6 services + BFF) must collectively fit within < 2GB RAM on K3s. This means each service cannot exceed ~256 MB of memory at steady state.
2. **Fast startup:** 24/7 warehouse operations demand minimal deployment downtime. Services must start in < 5 seconds for rolling updates and auto-scaling responsiveness.
3. **Developer productivity:** Phase 0 is a small team building 10+ services. The framework must support live reload, automatic dependency provisioning, and minimal boilerplate.
4. **Kotlin support:** Primary development language is Kotlin (see [ADR-003](ADR-003-kotlin-java-strategy.md)). The framework must have first-class Kotlin support.
5. **Cloud-native:** The framework must integrate natively with Kubernetes (health checks, config maps, graceful shutdown), Kafka, PostgreSQL, and Redis.
6. **myWMS migration path:** The legacy system uses Java EE (CDI, JPA, JAX-RS, JTA). A framework with compatible APIs reduces the conceptual gap for migrating business logic.
7. **Production readiness:** Fault tolerance (circuit breakers, retries, timeouts), observability (metrics, tracing), and security (OIDC, RBAC) must be built-in, not bolted on.

## Decision
We will use **Quarkus 3.8+** as the microservices framework for all Karyo WMS services.

### Key Quarkus Extensions

| Extension | Purpose |
|-----------|---------|
| `quarkus-resteasy-reactive-kotlin` | REST APIs with Kotlin coroutines support |
| `quarkus-hibernate-orm-panache-kotlin` | ORM with Kotlin-friendly repository pattern |
| `quarkus-jdbc-postgresql` | PostgreSQL driver |
| `quarkus-flyway` | Database migrations |
| `quarkus-smallrye-reactive-messaging-kafka` | Kafka producer/consumer |
| `quarkus-redis-client` | Redis caching and sessions |
| `quarkus-smallrye-fault-tolerance` | Circuit breaker, retry, timeout |
| `quarkus-smallrye-openapi` | OpenAPI 3.0 spec generation |
| `quarkus-smallrye-health` | Kubernetes liveness/readiness probes |
| `quarkus-micrometer-registry-prometheus` | Prometheus metrics |
| `quarkus-opentelemetry` | Distributed tracing |
| `quarkus-oidc` | Keycloak/OAuth2 integration |
| `quarkus-container-image-jib` | Container images without Dockerfile |
| `quarkus-kubernetes` | Kubernetes manifest generation |
| `quarkus-langchain4j` | LangChain4j integration (AI service) |

### Performance Characteristics

| Metric | Quarkus (JVM) | Quarkus (Native) | Spring Boot 3.x |
|--------|--------------|-----------------|-----------------|
| Startup time | 1-3 seconds | 0.02-0.1 seconds | 5-15 seconds |
| Memory (idle) | 80-120 MB | 20-40 MB | 150-300 MB |
| Memory (under load) | 150-256 MB | 50-100 MB | 250-512 MB |
| First response time | < 100 ms | < 10 ms | 1-5 seconds |

### Dev Services

Quarkus Dev Services automatically provisions backing services during local development:
- PostgreSQL: Testcontainers-managed PostgreSQL instance, auto-configured
- Kafka: Redpanda dev container, zero config
- Redis: Redis container, auto-configured
- Keycloak: Dev services Keycloak for auth testing

This eliminates the need for `docker-compose.yml` files or local installations during development.

### Live Reload

`quarkus dev` mode provides:
- Hot reload on source change (< 1 second for Kotlin)
- Continuous testing (tests re-run on change)
- Dev UI for inspecting beans, config, endpoints, Kafka messages
- No JVM restart required for most changes

## Consequences

### Positive
- **Memory footprint:** 150-256 MB per service in JVM mode, enabling 6 core services + infrastructure within 2GB at the edge. Native compilation available as an optimization lever if needed.
- **Startup time:** 1-3 seconds (JVM mode) enables fast rolling updates and responsive HPA scaling for the 24/7 warehouse operation. Kubernetes startup probes can use 5-second timeouts.
- **Developer velocity:** Dev Services eliminate "works on my machine" issues. Live reload means developers see changes in < 1 second without restarting. Continuous testing provides immediate feedback.
- **Java EE API compatibility:** Quarkus uses CDI, JAX-RS, JPA (Hibernate), and JTA — the same APIs as myWMS. Business logic migration from myWMS requires less conceptual translation than switching to a fundamentally different programming model (e.g., Spring's dependency injection vs. CDI).
- **Kotlin-first:** Quarkus has dedicated Kotlin extensions (Panache Kotlin, RESTEasy Reactive Kotlin) that leverage Kotlin idioms (coroutines, data classes, null safety).
- **Native compilation option:** GraalVM native images provide 20-40 MB footprint and < 100 ms startup for edge deployments where RAM is critically constrained. This is a future optimization, not required for Phase 0-1.
- **Extension ecosystem:** 400+ extensions cover all Karyo requirements without custom integration work.
- **Kubernetes-native:** Generated K8s manifests, health check endpoints, ConfigMap/Secret injection, graceful shutdown with Kafka consumer rebalancing.

### Negative
- **Smaller community than Spring:** Fewer Stack Overflow answers, blog posts, and third-party guides compared to Spring Boot. Team members with Spring experience will need onboarding.
- **Native compilation complexity:** GraalVM native image requires reflection configuration for JPA entities and CDI beans. Kotlin-specific issues (inline classes, companion objects) can require manual hints. This is only relevant if we pursue native compilation.
- **Faster release cadence:** Quarkus releases every 3-4 weeks. While this brings features and fixes quickly, it requires regular dependency updates. Mitigated by using the Quarkus BOM for version alignment.
- **Less mature ecosystem for some extensions:** Some Quarkus extensions (e.g., LangChain4j) are newer and may have fewer production references compared to Spring equivalents. Mitigated by the active Quarkus community and Red Hat backing.

### Neutral
- Quarkus uses build-time optimization (annotation processing, class transformation) rather than runtime reflection. This changes the mental model slightly but is transparent to application developers.
- The Quarkus testing framework (`@QuarkusTest`) uses JUnit 5 and integrates well with MockK for Kotlin. Testing patterns are similar to Spring's `@SpringBootTest`.

## Alternatives Considered

### Alternative 1: Spring Boot 3.x
- **Pros**: Largest Java framework community, most third-party library integrations, extensive documentation, most developers already have experience, Spring Cloud for distributed systems patterns.
- **Cons**: Higher memory consumption (250-512 MB per service under load — 6 services alone would exceed 1.5 GB at the edge), slower startup (5-15 seconds — impacts rolling update speed and HPA responsiveness), Spring Native (GraalVM) is less mature than Quarkus native, heavier annotation processing at runtime.
- **Why rejected**: The edge deployment constraint (< 2GB total for 6 core services + PostgreSQL + Kafka + Redis) is the primary reason. At 250-512 MB per service, Spring Boot would consume 1.5-3 GB for services alone, leaving no room for backing services. Spring's faster startup is achievable but requires Spring AOT processing and careful configuration, closing some of the gap but not enough for the edge constraint.

### Alternative 2: Micronaut
- **Pros**: Compile-time DI (no reflection), low memory footprint comparable to Quarkus, fast startup, GraalVM native support, Kotlin-friendly.
- **Cons**: Smaller ecosystem than both Spring and Quarkus, no Java EE API compatibility (different DI, different web framework), fewer production references in warehouse/logistics domain, no Dev Services equivalent for automatic backing service provisioning.
- **Why rejected**: Micronaut's performance is comparable to Quarkus, but the lack of Java EE API compatibility increases the conceptual gap when migrating myWMS business logic (myWMS uses CDI, JAX-RS, JPA). Quarkus's Dev Services and extension ecosystem provide better developer experience for the Phase 0 team.

### Alternative 3: Helidon
- **Pros**: Oracle-backed, MicroProfile compliant, reactive-first architecture, lightweight.
- **Cons**: Smallest community among the alternatives, limited Kotlin support (no dedicated Kotlin extensions), no Dev Services, fewer extensions for Kafka/Redis/AI integration, uncertain long-term investment from Oracle.
- **Why rejected**: Community size and Kotlin support are insufficient for a project targeting 10+ services with Kotlin as the primary language. The extension ecosystem would require more custom integration work.

## Implementation Notes
- Use `quarkus create app` with Kotlin template for bootstrapping new services.
- Gradle Kotlin DSL for build configuration (see project build standards).
- `application.properties` with environment variable overrides for 12-factor compliance. Profile-specific: `application-dev.properties`, `application-prod.properties`.
- Quarkus BOM (`io.quarkus.platform:quarkus-bom:3.8.x`) for dependency version alignment across all services.
- GraalVM native compilation is deferred to Phase 3+ — JVM mode meets edge constraints, and native adds build complexity.
- Pin Quarkus version across all services using a shared Gradle version catalog.

## Related Decisions
- [ADR-001: Microservices Architecture Style](superseded/ADR-001-microservices-architecture.md)
- [ADR-003: Kotlin + Java Dual Language Strategy](ADR-003-kotlin-java-strategy.md)
- [ADR-005: PostgreSQL as Primary Database](ADR-005-postgresql-database.md)
- [ADR-007: Kubernetes as Orchestration Platform](superseded/ADR-007-kubernetes-orchestration.md)
- [ADR-014: Flyway for Database Migrations](ADR-014-flyway-migrations.md)

## References
- Quarkus: https://quarkus.io/
- Quarkus vs Spring Boot benchmarks: https://quarkus.io/blog/runtime-performance/
- Quarkus Kotlin guide: https://quarkus.io/guides/kotlin
- Quarkus Dev Services: https://quarkus.io/guides/dev-services
- Quarkus extension registry: https://quarkus.io/extensions/

## Revision History
- 2026-02-15: Initial version
