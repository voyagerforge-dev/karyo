plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    implementation(project(":services:document-service:karyo-docstore-api"))

    implementation(project(":libs:karyo-common"))
    implementation(project(":libs:karyo-security"))
    implementation(project(":libs:karyo-events"))
    implementation(project(":libs:karyo-documents"))

    // Quarkus BOM
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Standard Quarkus extensions
    implementation(libs.quarkus.hibernate.orm.panache.kotlin)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.jackson.module.kotlin)
}
