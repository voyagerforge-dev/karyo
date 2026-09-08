plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.jakarta.persistence.api)
    compileOnly(libs.jakarta.validation.api)
}
