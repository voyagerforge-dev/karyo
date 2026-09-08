plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // API contract
    implementation(project(":services:auth-service:karyo-auth-api"))

    // Shared infrastructure libraries
    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-events"))
    implementation(project(":libs:karyo-security"))

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
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)

    // Jackson Kotlin module (NullIsSameAsDefault for Kotlin data class defaults)
    implementation(libs.jackson.module.kotlin)

    // Service-specific extensions
    implementation(libs.quarkus.keycloak.admin.rest.client)
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
    testImplementation(libs.quarkus.pact.provider)
}
