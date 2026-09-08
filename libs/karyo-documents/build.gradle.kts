plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // Quarkus BOM — manages the quarkus-qute extension version (no version.ref in the
    // catalog for a standalone lib module; mirror karyo-fulfillment-core/karyo-license).
    implementation(enforcedPlatform(libs.quarkus.bom))

    implementation(libs.quarkus.qute)
    implementation(libs.openhtmltopdf.pdfbox)

    // Needed for @ApplicationScoped bean discovery (DocumentRenderer) — compileOnly, provided
    // at runtime by karyo-app's quarkus-arc; mirror karyo-events/karyo-security's CDI dep style.
    compileOnly("jakarta.enterprise:jakarta.enterprise.cdi-api:4.1.0")
}
