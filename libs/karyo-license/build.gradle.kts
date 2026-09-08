plugins {
    id("karyo.kotlin-conventions")
}

dependencies {
    // Quarkus BOM — manages extension versions
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Needed for @ApplicationScoped bean discovery + @ConfigProperty injection.
    // No `quarkus-arc` / `quarkus-kotlin` alias in the version catalog for a
    // standalone lib module — mirror karyo-webhooks-core, the other module that
    // injects @ConfigProperty from a *-core library jar.
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.config.yaml)

    // ProblemDetail (used by LicenseRequiredExceptionMapper)
    implementation(project(":libs:karyo-common"))

    // JAX-RS API for LicenseRequiredExceptionMapper (@Provider, ExceptionMapper). compileOnly —
    // no version catalog alias for a standalone lib module; mirror karyo-common/karyo-security,
    // which pin the same coordinate directly. Provided at runtime by karyo-app's quarkus-rest.
    compileOnly("jakarta.annotation:jakarta.annotation-api")
    compileOnly("jakarta.ws.rs:jakarta.ws.rs-api:4.0.0")

    // SecurityIdentity (LicenseResource splits its body by authenticated vs anonymous caller).
    // compileOnly with the same pinned coordinate/version as libs/karyo-security's TenantFilter;
    // provided at runtime by karyo-app's quarkus-oidc.
    compileOnly("io.quarkus:quarkus-security:3.25.3")

    // Signed-license token parsing (LicenseVerifier) — plain tree-model JSON read, no Kotlin
    // module needed. Same pinned coordinate/version as libs/karyo-events (compileOnly there,
    // since ObjectMapper is CDI-injected; here it's `implementation` because LicenseVerifier
    // constructs its own ObjectMapper — it's a pure class with no CDI).
    implementation("com.fasterxml.jackson.core:jackson-databind:2.18.3")

    // Plain JUnit Jupiter for LicenseVerifierTest/LicenseServiceTest/LicenseKeygenTest:
    // no Quarkus/CDI test extension is needed for these directly constructed classes.
    // The enforced platform selects the JUnit version, overriding the request below;
    // useJUnitPlatform() is wired in the root build.gradle.kts subprojects block.
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

val operationalTestSourceSet = sourceSets.create("operationalTest")
configurations[operationalTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[operationalTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())
operationalTestSourceSet.compileClasspath += sourceSets.main.get().output + sourceSets.test.get().output
operationalTestSourceSet.runtimeClasspath += sourceSets.main.get().output + sourceSets.test.get().output
kotlin.target.compilations.named("operationalTest") {
    associateWith(kotlin.target.compilations.getByName("main"))
}

tasks.register<Test>("operationalTest") {
    description = "Runs controller-only license operations that require vendor secrets"
    group = "verification"
    testClassesDirs = operationalTestSourceSet.output.classesDirs
    classpath = operationalTestSourceSet.runtimeClasspath
    shouldRunAfter(tasks.test)
}

// Controller mint utility (LicenseMintOperationalTest): forward -Dkaryo.mint.* from the Gradle JVM into
// the test worker JVM (Gradle does not do this by default) and surface the test's stdout so
// scripts/mint-license.sh can grep the LICENSE_TOKEN= line. Property names are forwarded,
// never their values logged: the key file PATH is forwarded, the key itself is read in-test.
// Standard-out streaming is scoped to an actual mint run (karyo.mint.keyFile set) - unscoped it
// would also stream LicenseKeygenTest's unconditional PRIVATE_KEY_BASE64= println into every
// plain `test` run, including CI logs, which is a throwaway key but still violates "never print
// a private key" as a matter of policy.
tasks.withType<Test> {
    System.getProperties()
        .filterKeys { it.toString().startsWith("karyo.mint.") }
        .forEach { (k, v) -> systemProperty(k.toString(), v.toString()) }
    if (System.getProperty("karyo.mint.keyFile") != null) {
        testLogging.showStandardStreams = true
    }
}
