plugins {
    id("karyo.quarkus-service")
}

dependencies {
    // Domain modules (now libraries, each contributes entities, resources, services)
    // inventory-api is needed on the MAIN classpath too (not just test): the SC19
    // KeycloakEventPoller in com.karyo.app.auth maps events to JournalRecordType.
    implementation(project(":services:inventory-service:karyo-inventory-api"))
    implementation(project(":services:inventory-service:karyo-inventory-core"))
    // Deliberate build-time augmentation, never a runtime JAR upload. Off in ordinary images.
    if (providers.gradleProperty("karyoInventoryExample").map(String::toBooleanStrict).getOrElse(false)) {
        implementation(project(":services:inventory-service:karyo-inventory-ext-example"))
    }
    implementation(project(":services:product-service:karyo-product-core"))
    implementation(project(":services:warehouse-layout-service:karyo-layout-core"))
    implementation(project(":services:auth-service:karyo-auth-core"))
    implementation(project(":services:order-service:karyo-orders-core"))
    implementation(project(":services:task-service:karyo-tasks-core"))
    implementation(project(":services:fulfillment-service:karyo-fulfillment-core"))
    implementation(project(":services:replenishment-service:karyo-replenishment-core"))
    implementation(project(":services:stocktaking-service:karyo-stocktaking-core"))
    implementation(project(":services:work-service:karyo-work-api"))
    implementation(project(":services:work-service:karyo-work-core"))
    implementation(project(":services:integration-hub-service:karyo-webhooks-api"))
    implementation(project(":services:integration-hub-service:karyo-webhooks-core"))
    implementation(project(":services:reporting-service:karyo-reporting-api"))
    implementation(project(":services:reporting-service:karyo-reporting-core"))
    implementation(project(":services:ai-service:karyo-ai-api"))
    implementation(project(":services:ai-service:karyo-ai-core"))
    implementation(project(":services:demo-service:karyo-demo"))
    implementation(project(":services:document-service:karyo-docstore-core"))
    implementation(project(":libs:karyo-license"))
    implementation(project(":libs:karyo-documents"))
    implementation(project(":libs:karyo-sequence"))
    // Patchable<T> tri-state Jackson module (com.karyo.app.config.JacksonConfig) -- the
    // aggregator app is the one place that registers it as an ObjectMapperCustomizer.
    implementation(project(":libs:karyo-common"))

    // Quarkus BOM — manages all extension versions
    implementation(enforcedPlatform(libs.quarkus.bom))

    // Quarkus extensions must be present on the app classpath for augmentation.
    // They come transitively from the cores, but declare the union explicitly so
    // the aggregator app is the single source of truth for the assembled image.
    implementation(libs.quarkus.kotlin)
    implementation(libs.quarkus.rest)
    implementation(libs.quarkus.rest.jackson)
    implementation(libs.quarkus.rest.client.jackson)
    implementation(libs.quarkus.config.yaml)
    implementation(libs.quarkus.hibernate.orm.panache.kotlin)
    implementation(libs.quarkus.jdbc.postgresql)
    implementation(libs.quarkus.flyway)
    implementation(libs.quarkus.oidc)
    implementation(libs.quarkus.keycloak.admin.rest.client)
    implementation(libs.quarkus.hibernate.validator)
    implementation(libs.quarkus.smallrye.health)
    implementation(libs.quarkus.smallrye.fault.tolerance)
    implementation(libs.quarkus.micrometer.registry.prometheus)
    implementation(libs.quarkus.opentelemetry)
    implementation(libs.quarkus.logging.json)
    implementation(libs.quarkus.scheduler)
    implementation(libs.quarkus.mailer)
    implementation(libs.quarkus.qute)
    implementation(libs.openhtmltopdf.pdfbox)
    implementation(libs.quarkus.cache)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.quarkus.container.image.jib)
    implementation(libs.quarkus.kubernetes)

    // Test dependencies
    // The cores expose their api modules and shared libs as `implementation`, so the
    // migrated test suites need them declared explicitly on the test compile classpath.
    testImplementation(project(":services:inventory-service:karyo-inventory-api"))
    testImplementation(project(":services:product-service:karyo-product-api"))
    testImplementation(project(":services:warehouse-layout-service:karyo-layout-api"))
    testImplementation(project(":services:auth-service:karyo-auth-api"))
    testImplementation(project(":services:order-service:karyo-orders-api"))
    testImplementation(project(":services:task-service:karyo-tasks-api"))
    testImplementation(project(":services:fulfillment-service:karyo-fulfillment-api"))
    testImplementation(project(":services:replenishment-service:karyo-replenishment-api"))
    testImplementation(project(":services:stocktaking-service:karyo-stocktaking-api"))
    testImplementation(project(":services:work-service:karyo-work-api"))
    testImplementation(project(":services:monitoring-service:karyo-monitors-api"))
    testImplementation(project(":services:forecasting-service:karyo-forecasting-api"))
    testImplementation(project(":services:slotting-service:karyo-slotting-api"))
    testImplementation(project(":services:simulation-service:karyo-simulation-api"))
    testImplementation(project(":services:document-service:karyo-docstore-api"))
    testImplementation(project(":services:crossdock-service:karyo-crossdock-api"))
    testImplementation(project(":services:wave-service:karyo-wave-api"))
    testImplementation(project(":services:streaming-service:karyo-streaming-api"))
    testImplementation(project(":libs:karyo-common"))
    testImplementation(project(":libs:karyo-events"))
    testImplementation(project(":libs:karyo-security"))
    testImplementation(project(":libs:karyo-license"))
    testImplementation(libs.quarkus.junit5)
    testImplementation(libs.quarkus.junit5.mockito)
    testImplementation(libs.quarkus.test.security.oidc)
    testImplementation(libs.quarkus.devservices.keycloak)
    testImplementation(libs.rest.assured)
    testImplementation(libs.mockk)
    testImplementation(libs.assertj.core)
    testImplementation(libs.quarkus.pact.provider)
    testImplementation(libs.quarkus.pact.consumer)
}

// Task 4 review fix-round: an aggregate run spanning every touched module's test classes in one
// JVM fork (com.karyo.sequence/.fulfillment/.orders/.tasks/.stocktaking/.inventory/.app/.work --
// several hundred @QuarkusTest classes) exhausted Gradle's unconfigured test-worker default heap
// (observed ~512m) with a Hibernate/ANTLR HQL-parsing OutOfMemoryError partway through, not a
// logic bug in any test. No maxHeapSize was set anywhere before this. 3g comfortably covers the
// accumulated Hibernate query-plan/ANTLR parser state across a run this size.
// Wave bulk-fulfillment sprint (2026-08-21): the sprint's new @QuarkusTest/@TestProfile classes,
// each restarting the test app in-JVM, outgrew 3g with a vertx-blocked-thread-checker
// OutOfMemoryError partway through the full suite, so it is raised to 5g.
tasks.withType<Test> {
    maxHeapSize = "5g"

    // Forward the pact-jvm knobs from the Gradle invocation into the FORKED test JVM.
    //
    // Without this, `./gradlew :services:karyo-app:test -Dpact.broker.url=...` sets the property
    // on the Gradle daemon only: a Test task forks its own JVM and inherits no command-line -D at
    // all. Measured 2026-09-02 with a throwaway Gradle project -- `System.getProperty` and
    // `System.getenv` for the key both come back null inside the test worker. So CI's
    // `-Dpact.broker.url=http://127.0.0.1:9292` was decorative, and the four *PactProviderTest
    // classes were falling back to the literal default in
    // `@PactBroker(url = "${pact.broker.url:http://localhost:9292}")`.
    //
    // On the current CI host that default happens to work -- `localhost` resolves to ::1 first,
    // but a rootless-Podman published port answers on both families there today (measured, same
    // date, curl and a JVM both got HTTP 200 on `localhost` and on `127.0.0.1`). So this is
    // hardening, not a repair: nothing is known to have gone unverified. It is worth doing
    // anyway, because the configured URL silently not applying is one host change away from
    // being a silent pass -- `@IgnoreNoPactsToVerify(ignoreIoErrors = "true")` is hardcoded on
    // all four classes, so an unreachable broker verifies zero interactions and exits zero.
    //
    // The CI job additionally asserts that the number of verified interactions equals the number
    // published, which closes that hole from the other side.
    listOf("pact.broker.url", "pact.ignore.io.errors").forEach { key ->
        providers.systemProperty(key).orNull?.let { systemProperty(key, it) }
    }
}
