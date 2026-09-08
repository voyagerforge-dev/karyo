plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
    gradlePluginPortal()
}

dependencies {
    // Plugin dependencies — versions MUST match gradle/libs.versions.toml
    implementation("io.quarkus:io.quarkus.gradle.plugin:3.33.3.2")
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.10")
    implementation("org.jetbrains.kotlin:kotlin-allopen:2.3.10")
    implementation("org.jetbrains.kotlin:kotlin-noarg:2.3.10")
}
