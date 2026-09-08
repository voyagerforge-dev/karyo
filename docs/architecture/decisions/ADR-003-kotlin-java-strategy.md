# ADR-003: Kotlin + Java Dual Language Strategy

## Status
Accepted

## Context
Karyo WMS is migrating business logic from myWMS, which is written entirely in Java (Java EE). The team must decide the primary programming language for the new codebase. Key considerations include:

1. **Developer productivity:** We are building 10+ microservices with a small Phase 0 team. Language expressiveness directly impacts velocity.
2. **Code quality:** Warehouse management involves complex business rules (13-pass stock selection, multi-phase location finding, 18-state order machines). The language must help prevent bugs, especially null-related errors.
3. **Domain modeling:** WMS entities have rich value objects, sealed hierarchies (pick results, errors), and configuration-driven behavior (OrderStrategy, StorageStrategy). The language should make these patterns concise.
4. **Library ecosystem:** The Java ecosystem (Hibernate, Kafka clients, LangChain4j) must remain accessible.
5. **Hiring:** Developers with Kotlin experience are increasingly common, especially in the Android and backend communities.
6. **myWMS migration:** Business logic from myWMS Java must be translated, not copied line-by-line, to take advantage of modern patterns.

## Decision
We will use **Kotlin 1.9+** as the primary language for all new Karyo WMS code, running on **Java 21 LTS**.

### Language Usage Guidelines

| Scope | Language | Rationale |
|-------|----------|-----------|
| Service business logic | Kotlin | Conciseness, null safety, sealed classes, coroutines |
| Domain entities (JPA) | Kotlin | Data classes for DTOs, regular classes for JPA entities (Hibernate requires mutable fields) |
| REST resources (JAX-RS) | Kotlin | Extension functions, suspend functions for reactive |
| Kafka producers/consumers | Kotlin | Coroutines for async processing |
| Build scripts | Kotlin DSL | Type-safe Gradle configuration |
| Generated code (OpenAPI, Protobuf) | Java | Code generators typically output Java; Kotlin interop is seamless |
| Framework extension points | Java (if required) | Some Quarkus extensions expect Java annotations; rare cases |
| Test code | Kotlin | MockK for mocking (Kotlin-native), data classes for test fixtures |

### Kotlin Features Leveraged

**Null Safety:**
```kotlin
// myWMS Java: NullPointerException risk everywhere
String lotNumber = stockUnit.getLotNumber(); // Could be null
if (lotNumber != null) { ... }

// Karyo Kotlin: Compiler-enforced null safety
val lotNumber: String? = stockUnit.lotNumber
lotNumber?.let { validateLot(it) }
```

**Data Classes for DTOs:**
```kotlin
data class StockUnitResponse(
    val id: Long,
    val itemNumber: String,
    val amount: BigDecimal,
    val locationName: String,
    val state: StockState,
)
```

**Sealed Classes for Domain Results:**
```kotlin
sealed class PickResult {
    data class Success(val pickedAmount: BigDecimal) : PickResult()
    data class InsufficientStock(val available: BigDecimal) : PickResult()
    data class LocationLocked(val lockType: Int) : PickResult()
    data class ItemNotFound(val itemNumber: String) : PickResult()
}

// Exhaustive when — compiler enforces all cases handled
fun handleResult(result: PickResult): Response = when (result) {
    is PickResult.Success -> Response.ok(result).build()
    is PickResult.InsufficientStock -> Response.status(409).entity(result).build()
    is PickResult.LocationLocked -> Response.status(423).entity(result).build()
    is PickResult.ItemNotFound -> Response.status(404).entity(result).build()
}
```

**Extension Functions:**
```kotlin
fun BigDecimal.withScale(scale: Int): BigDecimal =
    this.setScale(scale, RoundingMode.HALF_UP)

fun StockUnit.isAvailableForPicking(): Boolean =
    state == StockState.ON_STOCK.code && lock == LockType.UNLOCKED && availableAmount > BigDecimal.ZERO
```

**Coroutines (for reactive I/O):**
```kotlin
@Path("/api/v1/stock-units")
class StockUnitResource(private val stockService: StockService) {
    @GET
    suspend fun list(@BeanParam params: StockQueryParams): PaginatedResponse<StockUnitResponse> =
        stockService.findAll(params).toResponse()
}
```

### myWMS Migration Approach

myWMS Java code is **analyzed for domain logic and rewritten in idiomatic Kotlin** — behavior is reimplemented fresh, never translated line-by-line. Examples:

- Java `if (x != null) { if (y != null) { ... } }` chains become Kotlin null-safe operators and `let`/`also` scopes.
- Java `BusinessException` with error codes becomes Kotlin sealed result classes.
- Java entity getters/setters become Kotlin properties.
- Java `for` loops with complex filtering become Kotlin sequence/collection operations.
- Java CDI `@Inject` fields become Kotlin constructor injection (Quarkus supports both).

## Consequences

### Positive
- **Conciseness:** Kotlin typically requires 30-50% fewer lines than equivalent Java. With 10,000+ lines of myWMS business logic to migrate, this significantly reduces codebase size and maintenance burden.
- **Null safety:** The Kotlin compiler prevents the class of `NullPointerException` bugs that are common in WMS operations (null lot numbers, null strategy dates, null locations during transfer). This is critical for a system where a null reference could misroute inventory.
- **Sealed classes:** Perfect for modeling WMS domain results where multiple outcomes are possible (pick success, insufficient stock, location locked, item not found). Compiler-enforced exhaustive `when` expressions prevent missing error cases.
- **Data classes:** Automatic `equals`/`hashCode`/`toString`/`copy` generation for DTOs, events, and value objects. Reduces boilerplate significantly.
- **Extension functions:** Allow adding domain-specific behavior to entities without subclassing (e.g., `StockUnit.isAvailableForPicking()`).
- **Coroutine support:** Quarkus RESTEasy Reactive + Kotlin coroutines enable non-blocking I/O without callback complexity.
- **Java interop:** 100% interoperable with Java libraries. All myWMS dependencies, Hibernate, Kafka clients, and LangChain4j work seamlessly from Kotlin.
- **Modern testing:** MockK provides Kotlin-native mocking with coroutine support, more expressive than Mockito for Kotlin code.

### Negative
- **JPA entity limitations:** Hibernate requires no-arg constructors and mutable (var) properties on JPA entities. Kotlin data classes are not ideal for JPA entities (though `kotlin-jpa` compiler plugin mitigates this). JPA entities must use `class` (not `data class`) with `lateinit var` fields.
- **Build time:** Kotlin compilation is slightly slower than Java (10-20% longer for initial build). Mitigated by Gradle build cache, incremental compilation, and Quarkus live reload (no full rebuild in dev mode).
- **Team onboarding:** Developers experienced only in Java need Kotlin training. Mitigated by Kotlin's gentle learning curve (can write "Java-like Kotlin" initially and adopt idioms progressively).
- **Debugging:** Stack traces from Kotlin coroutines and inline functions can be harder to read than plain Java stack traces. Mitigated by Kotlin's debugger support in IntelliJ IDEA.

### Neutral
- Kotlin compiles to the same JVM bytecode as Java. Runtime performance is identical for the same algorithm. There is no performance trade-off.
- IntelliJ IDEA (the recommended IDE) has best-in-class Kotlin support from JetBrains, who created the language.

## Alternatives Considered

### Alternative 1: Pure Java 21
- **Pros**: Largest developer pool, no language learning curve, records for DTOs, pattern matching improvements, virtual threads (Project Loom), no JPA compatibility issues.
- **Cons**: More verbose (records are limited compared to data classes — no copy, no default values, no mutable fields), no sealed class exhaustiveness checking in `switch` (Java 21 preview feature, less mature), no null safety (relies on `@Nullable` annotations and discipline), no extension functions (requires utility classes), no coroutines (virtual threads are an alternative but less composable).
- **Why rejected**: While Java 21 has closed the gap significantly with records and pattern matching, Kotlin's null safety alone justifies the switch for a WMS where null references can cause inventory misrouting. The 30-50% reduction in code size matters when building 10+ services. Java 21's virtual threads are compelling but Quarkus RESTEasy Reactive with Kotlin coroutines provides a more structured concurrency model.

### Alternative 2: Scala
- **Pros**: Powerful type system, pattern matching, immutable-first design, functional programming support, Akka ecosystem for distributed systems.
- **Cons**: Steeper learning curve (implicits, monads, type-level programming), much smaller developer pool than Kotlin, slower compilation, binary compatibility issues between Scala versions, Quarkus has limited Scala support (no dedicated extensions), poor IDE experience outside IntelliJ with the Scala plugin.
- **Why rejected**: Scala's power comes with complexity that is not justified for WMS domain logic. The WMS domain is inherently imperative (state machines, sequential workflows) rather than functional. Kotlin provides sufficient type safety and expressiveness with a much gentler learning curve and better framework support.

## Implementation Notes
- Use `kotlin-jpa` compiler plugin to generate no-arg constructors for JPA entities automatically.
- Use `kotlin-allopen` compiler plugin to open JPA entity classes for Hibernate proxying.
- JPA entities: use `class` with `lateinit var` for required fields, `var` with defaults for optional fields. Do NOT use `data class` for JPA entities.
- DTOs, events, value objects: use `data class` freely.
- Domain results: use `sealed class` hierarchies.
- Configure `detekt` for Kotlin static analysis in CI/CD pipeline.
- IDE: IntelliJ IDEA Community or Ultimate (both have full Kotlin support).

## Related Decisions
- [ADR-002: Quarkus as Microservices Framework](ADR-002-quarkus-framework.md)
- [ADR-005: PostgreSQL as Primary Database](ADR-005-postgresql-database.md)

## References
- Kotlin language: https://kotlinlang.org/
- Kotlin for server-side: https://kotlinlang.org/docs/server-overview.html
- Quarkus Kotlin guide: https://quarkus.io/guides/kotlin
- Hibernate with Kotlin: https://quarkus.io/guides/hibernate-orm-panache-kotlin
- MockK: https://mockk.io/

## Revision History
- 2026-02-15: Initial version
