plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // Minimal deps — this is the published contract
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.jakarta.persistence.api)
    compileOnly(libs.jakarta.validation.api)
}
