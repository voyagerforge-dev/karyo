plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    implementation(project(":libs:karyo-common"))

    // Quarkus BOM — manages extension versions used below.
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Needed for @ApplicationScoped bean discovery + @ConfigProperty injection (SequenceConfig)
    // from a standalone lib module — no quarkus-arc/quarkus-kotlin alias exists for that use
    // case on its own. Mirrors libs/karyo-license and karyo-webhooks-core.
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.config.yaml)

    // Entity (SequenceNumberState) + repository + @Transactional(REQUIRES_NEW) pessimistic-lock
    // increment (FormattedCounterGenerator) — compileOnly, provided at runtime by karyo-app's
    // quarkus-hibernate-orm-panache-kotlin. Mirrors libs/karyo-events (transitively resolves
    // jakarta.transaction-api for @Transactional too, same as karyo-events' compileOnly set).
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
    compileOnly("jakarta.persistence:jakarta.persistence-api:3.2.0")
    compileOnly("org.hibernate.orm:hibernate-core:6.6.13.Final")
    compileOnly("io.quarkus:quarkus-hibernate-orm-panache-kotlin:3.25.3")

    // JAX-RS API for SequenceExceptionMapper (@Provider, ExceptionMapper) — compileOnly, provided
    // at runtime by karyo-app's quarkus-rest. Mirrors libs/karyo-license.
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")
}
