plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // API contract
    implementation(project(":services:task-service:karyo-tasks-api"))

    // Work inbox SPI — TransportWorkProvider implements WorkProvider
    implementation(project(":services:work-service:karyo-work-api"))

    // Sibling module API contracts (in-process lookups + SPIs + shared event payloads)
    implementation(project(":services:order-service:karyo-orders-api"))           // GoodsReceiptLineReceivedEvent + OrderState
    implementation(project(":services:warehouse-layout-service:karyo-layout-api")) // LocationFinder
    implementation(project(":services:inventory-service:karyo-inventory-api"))     // UnitLoadLookup + UnitLoadMover

    // Shared infrastructure libraries
    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-events"))
    implementation(project(":libs:karyo-security"))
    implementation(project(":libs:karyo-sequence"))   // SequenceNumberService (SC17 generation-site rollout)

    // Quarkus BOM — manages all extension versions
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Standard Quarkus extensions
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.jackson.module.kotlin)
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
    implementation(libs.quarkus.scheduler)
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
}
