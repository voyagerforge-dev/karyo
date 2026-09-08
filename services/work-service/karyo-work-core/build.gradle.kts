plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    implementation(project(":services:work-service:karyo-work-api"))
    implementation(project(":services:warehouse-layout-service:karyo-layout-api")) // WorkingAreaLookup (Task 8 ?workingAreaId= filter)

    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-events"))
    implementation(project(":libs:karyo-security"))

    implementation(enforcedPlatform(libs.quarkus.bom))
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
    implementation(libs.quarkus.container.image.jib)
    implementation(libs.quarkus.kubernetes)

    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.junit5.mockito)
    testImplementation(libs.quarkus.test.security.oidc)
    testImplementation(libs.quarkus.devservices.keycloak)
    testImplementation(libs.rest.assured)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
}
