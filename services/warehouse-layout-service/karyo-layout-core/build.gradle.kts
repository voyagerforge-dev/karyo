plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // API contract
    implementation(project(":services:warehouse-layout-service:karyo-layout-api"))

    // Sibling module API contracts (in-process lookups + CDI event payloads)
    implementation(project(":services:product-service:karyo-product-api"))
    implementation(project(":services:inventory-service:karyo-inventory-api"))

    // Shared infrastructure libraries
    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-events"))
    implementation(project(":libs:karyo-security"))
    implementation(project(":libs:karyo-documents"))  // DocumentRenderer (D11 storage-location ZPL label)

    // Quarkus BOM
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Standard Quarkus extensions
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.hibernate.orm.panache.kotlin)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)

    // Service-specific extensions
    implementation(libs.quarkus.cache)         // Caffeine L1 caching
    implementation(libs.quarkus.scheduler)     // Outbox processor polling
    implementation(libs.quarkus.container.image.jib)
    implementation(libs.quarkus.kubernetes)

    // Cross-service REST calls
    implementation(libs.quarkus.rest.client.jackson)
    implementation(libs.quarkus.smallrye.fault.tolerance)

    // Jackson Kotlin module (NullIsSameAsDefault for Kotlin data class defaults)
    implementation(libs.jackson.module.kotlin)

    // Test dependencies
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.junit5.mockito)
    testImplementation(libs.quarkus.test.security.oidc)
    testImplementation(libs.quarkus.devservices.keycloak)
    testImplementation(libs.rest.assured)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.quarkus.pact.provider)
    testImplementation(libs.quarkus.pact.consumer)
}
