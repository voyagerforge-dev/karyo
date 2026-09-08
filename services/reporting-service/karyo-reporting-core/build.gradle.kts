plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    implementation(project(":services:reporting-service:karyo-reporting-api"))

    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-security"))

    // Quarkus BOM
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Standard Quarkus extensions
    implementation(libs.quarkus.hibernate.orm.panache.kotlin)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.jackson.module.kotlin)
    // Bean Validation (@Valid on ReportDefinitionResource — B19)
    implementation(libs.quarkus.hibernate.validator)
}
