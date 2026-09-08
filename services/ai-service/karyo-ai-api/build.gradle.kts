plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    implementation(enforcedPlatform(libs.quarkus.bom))
    compileOnly(libs.jackson.module.kotlin)
}
