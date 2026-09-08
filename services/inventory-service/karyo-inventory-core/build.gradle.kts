plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // API contract
    implementation(project(":services:inventory-service:karyo-inventory-api"))

    // Sibling module API contracts (CDI event payloads)
    implementation(project(":services:product-service:karyo-product-api"))
    implementation(project(":services:order-service:karyo-orders-api"))  // GoodsReceiptLookup (supplier/ASN/received read)
    implementation(project(":services:auth-service:karyo-auth-api"))     // ClientLookup (changeClient target validation)
    // A2-1: first inventory→layout edge — api-only, deliberate (six-hard-items §2.6)
    implementation(project(":services:warehouse-layout-service:karyo-layout-api"))

    // Shared infrastructure libraries
    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-events"))
    implementation(project(":libs:karyo-security"))
    implementation(project(":libs:karyo-documents"))  // DocumentRenderer (D8 unit-load content-list PDF)
    implementation(project(":libs:karyo-sequence"))   // SequenceNumberService (SC17 generation-site rollout)

    // Quarkus BOM — manages all extension versions
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
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)

    // Service-specific extensions (NOT used by all services)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.cache)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.rest.client.jackson)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.quarkus.container.image.jib)
    implementation(libs.quarkus.kubernetes)

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
