plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // BOM needed to resolve jackson-module-kotlin version (no standalone version pin)
    implementation(enforcedPlatform(libs.quarkus.bom))
    // Jackson databind (for DTO serialisation) — version managed by Quarkus BOM
    compileOnly(libs.jackson.module.kotlin)
    // Bean Validation annotations for CreateReportDefinitionRequest (B19)
    compileOnly(libs.jakarta.validation.api)
}
