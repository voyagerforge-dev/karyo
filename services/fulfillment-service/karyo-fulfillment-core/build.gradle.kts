plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // API contract
    implementation(project(":services:fulfillment-service:karyo-fulfillment-api"))

    // Sibling module API contracts (in-process lookups + SPIs + shared event payloads)
    implementation(project(":services:order-service:karyo-orders-api"))            // DeliveryOrderLookup, OrderProgressionPort, OrderState
    implementation(project(":services:inventory-service:karyo-inventory-api"))      // StockPicker, StockUnitLookup, UnitLoadLookup
    implementation(project(":services:warehouse-layout-service:karyo-layout-api"))  // StagingLocationLookup
    implementation(project(":services:product-service:karyo-product-api"))          // SubstitutionLookup (3.2b-2; harmless to wire now)
    implementation(project(":services:work-service:karyo-work-api"))               // WorkProvider SPI (PickWorkProvider)
    implementation(project(":services:auth-service:karyo-auth-api"))               // RuntimePropertyLookup SPI (SC16 runtime config)

    // Shared infrastructure libraries
    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-events"))
    implementation(project(":libs:karyo-security"))
    implementation(project(":libs:karyo-documents"))
    implementation(project(":libs:karyo-sequence"))   // SequenceNumberService (SC17 generation-site rollout)

    // Quarkus BOM
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
    implementation(libs.quarkus.qute)
    implementation(libs.openhtmltopdf.pdfbox)
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
