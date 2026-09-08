# ADR-028: Gradle Kotlin DSL as Build Tool

## Status
Accepted

**Current implementation:** The dependency and convention-plugin examples below are historical,
including their test artifact names. Use the [version catalog](../../../gradle/libs.versions.toml),
[convention plugins](../../../buildSrc/src/main/kotlin/) and
[app build](../../../services/karyo-app/build.gradle.kts) rather than copying these examples.
See the [ADR index](README.md#adr-index) for the build decision's current disposition.

## Context
Karyo WMS consists of 9+ microservices, each with its own build, test, and containerization requirements. The build system must support:

- **Multi-module projects**: Each service may have sub-modules (api, core, ext) for clean dependency separation
- **Kotlin compilation**: Primary language is Kotlin with Java interop
- **Quarkus integration**: Dev mode, native build support, Jib containerization
- **Dependency management**: Consistent dependency versions across all services
- **CI/CD integration**: Fast builds in GitHub Actions, caching support
- **Developer experience**: Fast incremental builds, IDE integration, live reload

The build system must also support a monorepo or multi-repo approach — currently planned as a monorepo where all services share a root build configuration.

## Decision
We will use **Gradle 8.x with Kotlin DSL** (`build.gradle.kts`) as the build tool for all Karyo WMS services.

**Root Project Structure:**

```
karyo/
├── settings.gradle.kts              # Module definitions
├── build.gradle.kts                 # Root build config (shared plugins, repos)
├── gradle/
│   ├── libs.versions.toml           # Version catalog
│   └── wrapper/
│       └── gradle-wrapper.properties
├── buildSrc/                        # Convention plugins
│   ├── build.gradle.kts
│   └── src/main/kotlin/
│       ├── karyo.kotlin-conventions.gradle.kts
│       ├── karyo.quarkus-service.gradle.kts
│       └── karyo.testing-conventions.gradle.kts
├── services/
│   ├── inventory-service/
│   │   ├── build.gradle.kts
│   │   └── src/
│   ├── order-service/
│   │   ├── build.gradle.kts
│   │   └── src/
│   └── ...
├── libs/
│   ├── karyo-common/               # Shared domain types, utilities
│   │   ├── build.gradle.kts
│   │   └── src/
│   └── karyo-test-support/         # Shared test utilities
│       ├── build.gradle.kts
│       └── src/
└── frontend/                        # React frontend (separate build)
```

**Version Catalog (`gradle/libs.versions.toml`):**

```toml
[versions]
kotlin = "1.9.22"
quarkus = "3.8.2"
quarkus-plugin = "3.8.2"
mockk = "1.13.9"
assertj = "3.25.3"
testcontainers = "1.19.5"
rest-assured = "5.4.0"

[libraries]
# Quarkus BOM (manages most dependencies)
quarkus-bom = { module = "io.quarkus.platform:quarkus-bom", version.ref = "quarkus" }

# Kotlin
kotlin-stdlib = { module = "org.jetbrains.kotlin:kotlin-stdlib", version.ref = "kotlin" }

# Testing
mockk = { module = "io.mockk:mockk", version.ref = "mockk" }
assertj-core = { module = "org.assertj:assertj-core", version.ref = "assertj" }
testcontainers-bom = { module = "org.testcontainers:testcontainers-bom", version.ref = "testcontainers" }
rest-assured = { module = "io.rest-assured:rest-assured", version.ref = "rest-assured" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlin-allopen = { id = "org.jetbrains.kotlin.plugin.allopen", version.ref = "kotlin" }
kotlin-jpa = { id = "org.jetbrains.kotlin.plugin.jpa", version.ref = "kotlin" }
quarkus = { id = "io.quarkus", version.ref = "quarkus-plugin" }
```

**Convention Plugin (`buildSrc/src/main/kotlin/karyo.quarkus-service.gradle.kts`):**

```kotlin
// Applied to every Quarkus microservice
plugins {
    id("karyo.kotlin-conventions")
    id("io.quarkus")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Standard Quarkus extensions for all services
    implementation("io.quarkus:quarkus-kotlin")
    implementation("io.quarkus:quarkus-resteasy-reactive-jackson")
    implementation("io.quarkus:quarkus-hibernate-orm-panache-kotlin")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-micrometer-registry-prometheus")
    implementation("io.quarkus:quarkus-opentelemetry")
    implementation("io.quarkus:quarkus-oidc")
    implementation("io.quarkus:quarkus-logging-json")
    implementation("io.quarkus:quarkus-smallrye-reactive-messaging-kafka")

    // Shared library
    implementation(project(":libs:karyo-common"))

    // Test dependencies
    testImplementation("io.quarkus:quarkus-junit5")
    testImplementation("io.quarkus:quarkus-junit5-mockito")
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.rest.assured)
    testImplementation(project(":libs:karyo-test-support"))
}

// Quarkus Jib container image configuration
quarkus {
    buildForkOptions {
        memoryMaximumSize.set("2g")
    }
}

tasks.withType<Test> {
    systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    useJUnitPlatform()
}
```

**Convention Plugin (`buildSrc/src/main/kotlin/karyo.kotlin-conventions.gradle.kts`):**

```kotlin
plugins {
    id("org.jetbrains.kotlin.jvm")
    id("org.jetbrains.kotlin.plugin.allopen")
    id("org.jetbrains.kotlin.plugin.jpa")
}

kotlin {
    jvmToolchain(21)
}

// Required for Quarkus CDI proxying
allOpen {
    annotation("jakarta.ws.rs.Path")
    annotation("jakarta.enterprise.context.ApplicationScoped")
    annotation("jakarta.enterprise.context.RequestScoped")
    annotation("jakarta.persistence.Entity")
    annotation("io.quarkus.test.junit.QuarkusTest")
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}
```

**Per-Service Build File (minimal):**

```kotlin
// services/inventory-service/build.gradle.kts
plugins {
    id("karyo.quarkus-service")
}

dependencies {
    // Service-specific dependencies only
    implementation("io.quarkus:quarkus-redis-client")
    implementation("io.quarkus:quarkus-cache")
}
```

**Key Gradle Tasks:**

| Task | Command | Purpose |
|------|---------|---------|
| Dev mode | `./gradlew :services:inventory-service:quarkusDev` | Live reload development |
| Build | `./gradlew :services:inventory-service:build` | Compile, test, package |
| Container | `./gradlew :services:inventory-service:quarkusBuild -Dquarkus.container-image.build=true` | Build container via Jib |
| All tests | `./gradlew test` | Run all tests across all modules |
| Single test | `./gradlew :services:inventory-service:test --tests "*StockServiceTest"` | Run specific test |

## Consequences

### Positive
- Kotlin DSL provides type-safe build scripts with IDE auto-completion — syntax errors caught at compile time, not runtime
- Convention plugins eliminate build script duplication — shared configuration applied consistently across all 9+ services
- Version catalog (`libs.versions.toml`) ensures consistent dependency versions across all modules
- Gradle's incremental compilation and build cache significantly reduce rebuild times (only recompile changed modules)
- Quarkus Gradle plugin integrates seamlessly with dev mode, Jib containerization, and native builds
- buildSrc convention plugins can be unit-tested like regular Kotlin code
- Gradle's configuration avoidance API (`tasks.register` vs `tasks.create`) reduces configuration time in large multi-module projects

### Negative
- Gradle's Kotlin DSL is slower to configure than Groovy DSL (Kotlin compilation overhead for build scripts)
- Gradle version upgrades can break plugins and require dependency updates across the build
- Learning curve for developers unfamiliar with Gradle — especially the difference between `api` vs `implementation`, `buildSrc` conventions, and version catalogs
- Gradle daemon memory consumption can be significant in CI (mitigated by using `--no-daemon` in CI or configuring daemon memory)
- Debug output from Gradle can be verbose and hard to parse compared to Maven

### Neutral
- Gradle wrapper (`gradlew`) ensures all developers and CI use the same Gradle version
- The Quarkus plugin may lag behind Maven plugin features in some releases — Quarkus team prioritizes Maven support slightly
- GitHub Actions has excellent Gradle caching support via `gradle/actions/setup-gradle`

## Alternatives Considered

### Alternative 1: Maven
- **Pros**: Mature and stable, XML-based configuration is familiar to most Java developers, extensive plugin ecosystem, Quarkus has first-class Maven support, predictable build lifecycle
- **Cons**: XML verbosity (pom.xml files are significantly longer than equivalent build.gradle.kts), slower incremental builds (no incremental compilation by default), parent POM inheritance model is less flexible than convention plugins, no type-safe build scripts, multi-module configuration requires extensive XML
- **Why rejected**: The verbosity of Maven XML is a significant developer experience issue for a project with 9+ services. Gradle's incremental build capabilities and Kotlin DSL type safety provide a better developer experience. Convention plugins are more powerful and flexible than Maven's parent POM inheritance.

### Alternative 2: Bazel
- **Pros**: Extremely fast builds at scale (deterministic, fine-grained caching), excellent monorepo support, language-agnostic, reproducible builds, remote build execution
- **Cons**: Steep learning curve (Starlark build language, BUILD files), limited Kotlin/Quarkus ecosystem support (few ready-made rules), significant setup overhead for small-to-medium projects, overkill for 9 services, small community for JVM projects compared to Gradle/Maven
- **Why rejected**: Bazel's benefits shine at very large scale (hundreds of services, thousands of developers). For 9 microservices with a small-to-medium team, the setup overhead and learning curve are disproportionate. Limited Quarkus integration would require custom build rules.

## Implementation Notes
- Initialize the Gradle wrapper at version 8.6+ with `gradle wrapper --gradle-version 8.6`
- Create `buildSrc/` with convention plugins before creating any service modules — this ensures consistency from the start
- Set up the version catalog (`gradle/libs.versions.toml`) with all Quarkus BOM-managed dependencies plus third-party testing and utility libraries
- Configure GitHub Actions with Gradle caching:
  ```yaml
  - uses: gradle/actions/setup-gradle@v3
    with:
      cache-read-only: ${{ github.ref != 'refs/heads/develop' }}
  ```
- Set Gradle daemon JVM args in `gradle.properties`: `org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC`
- Enable parallel execution: `org.gradle.parallel=true` in `gradle.properties`
- Enable configuration cache: `org.gradle.configuration-cache=true` for faster subsequent builds
- Create a `Makefile` or shell script aliases for common tasks to reduce command-line verbosity

## Related Decisions
- [ADR-002](ADR-002-quarkus-framework.md): Quarkus as Microservices Framework — Quarkus Gradle plugin is the primary build integration
- [ADR-003](ADR-003-kotlin-java-strategy.md): Kotlin + Java Dual Language Strategy — Kotlin DSL provides consistency between application code and build scripts
- [ADR-030](ADR-030-conventional-commits-semver.md): Conventional Commits — Gradle build generates version from git tags

## References
- [Gradle Kotlin DSL Documentation](https://docs.gradle.org/current/userguide/kotlin_dsl.html)
- [Gradle Version Catalogs](https://docs.gradle.org/current/userguide/platforms.html#sub::toml-dependencies-format)
- [Quarkus Gradle Plugin Guide](https://quarkus.io/guides/gradle-tooling)
- [Gradle Convention Plugins](https://docs.gradle.org/current/samples/sample_convention_plugins.html)
- [Gradle Build Cache](https://docs.gradle.org/current/userguide/build_cache.html)

## Revision History
- 2026-02-15: Initial version
