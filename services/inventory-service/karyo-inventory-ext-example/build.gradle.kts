plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // Production extension code compiles against APIs only, never a core.
    compileOnly(project(":services:inventory-service:karyo-inventory-api"))
    compileOnly(libs.jakarta.cdi.api)

    testImplementation(enforcedPlatform(libs.quarkus.bom))
    testImplementation(project(":services:inventory-service:karyo-inventory-api"))
    testImplementation(libs.jakarta.cdi.api)
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
}
