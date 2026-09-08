plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // Minimal deps — this is the published contract
    compileOnly(libs.jakarta.cdi.api)
    compileOnly(libs.jakarta.persistence.api)
    compileOnly(libs.jakarta.validation.api)

    // Patchable<T> tri-state wrapper (UpdateDeliveryOrderRequest, D2)
    implementation(project(":libs:karyo-common"))
    // @JsonInclude(NON_EMPTY) on the Patchable<String> properties (jackson-annotations only
    // -- no databind needed here).
    compileOnly("com.fasterxml.jackson.core:jackson-annotations:2.19.2")
}
