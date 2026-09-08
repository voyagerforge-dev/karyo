import com.karyo.conventions.VerifyJarLegalFiles
import groovy.json.JsonSlurper
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.api.tasks.testing.junitplatform.JUnitPlatformOptions

plugins {
    // Kotlin, allopen, jpa, and Quarkus plugins are managed by buildSrc convention plugins
    // (karyo.kotlin-conventions and karyo.quarkus-service) - do not redeclare here.
    alias(libs.plugins.detekt) apply false
    alias(libs.plugins.dependencyCheck) apply false
}

val gradleTestContract =
    ((JsonSlurper().parse(rootProject.file("config/test-runner-contracts.json")) as? Map<*, *>)
        ?.get("contracts") as? List<*>)
        ?.filterIsInstance<Map<*, *>>()
        ?.singleOrNull { it["runner"] == "gradle" }
        ?: throw GradleException("Missing Gradle test runner contract")
val deterministicTestTaskName = gradleTestContract["task_name"] as? String
    ?: throw GradleException("Gradle test runner contract has no task name")
if (gradleTestContract["require_unfiltered"] != true) {
    throw GradleException("Gradle test runner contract must reject test filters")
}
if (gradleTestContract["require_junit_platform"] != true) {
    throw GradleException("Gradle test runner contract must require JUnit Platform")
}

allprojects {
    group = "com.karyo"
    version = "2.0.1"

    repositories {
        mavenCentral()
    }
}

subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
            javaParameters.set(true)
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        systemProperty("java.util.logging.manager", "org.jboss.logmanager.LogManager")
    }

    pluginManager.withPlugin("java") {
        extensions.configure<JavaPluginExtension> {
            sourceCompatibility = JavaVersion.VERSION_21
            targetCompatibility = JavaVersion.VERSION_21
        }
    }

    // -----------------------------------------------------------------------------------
    // Licence notices inside every distributed jar (Apache-2.0 section 4(d)).
    //
    // 4(d) requires the NOTICE to accompany the distribution, and Karyo's NOTICE carries more
    // than attribution: it is the file that names the nine commercial `-core` modules as
    // outside the Apache grant. Until this block existed nothing packaged it, so a recipient
    // of a jar or of the image was handed the code without the statement of what it is
    // licensed under.
    //
    // The LICENSE half is per module on purpose. The licence boundary in this repository is
    // per Gradle module, not per service directory: a paid `-core` holds its own LICENSE
    // marker pointing at EULA-COMMERCIAL.md, and its sibling `-api` does not. Copying the
    // root Apache-2.0 text into a proprietary jar would state the opposite of what that
    // marker says, so each module packages the marker it actually has and only modules
    // without one get the root LICENSE.
    //
    // THIRD-PARTY-NOTICES is not per module: no module carries one, and the root inventory
    // is what covers the LGPL-2.1, EPL-2.0 and SIL-OFL-1.1 components a distribution
    // actually contains. The licence label deliberately does not enumerate those terms, so
    // this file is the vehicle that discloses them and it has to travel with the artifact.
    //
    // `metaInf` is silent about a missing source file, so the copy alone guarantees nothing:
    // `verifyJarLegalFiles` reads the built jars back and is what actually holds the line.
    // -----------------------------------------------------------------------------------
    pluginManager.withPlugin("java") {
        val noticeFile = rootProject.file("NOTICE")
        val licenseFile = file("LICENSE").takeIf { it.isFile } ?: rootProject.file("LICENSE")
        val thirdPartyFile = rootProject.file("THIRD-PARTY-NOTICES.md")

        tasks.withType<Jar>().configureEach {
            metaInf {
                from(noticeFile)
                from(licenseFile)
                from(thirdPartyFile) { rename { "THIRD-PARTY-NOTICES" } }
            }
        }

        val verifyJarLegalFiles = tasks.register<VerifyJarLegalFiles>("verifyJarLegalFiles") {
            group = "verification"
            description =
                "Fails unless every jar carries META-INF/NOTICE, META-INF/LICENSE and " +
                    "META-INF/THIRD-PARTY-NOTICES."
            jars.from(tasks.withType<Jar>())
            notice.set(noticeFile)
            license.set(licenseFile)
            thirdPartyNotices.set(thirdPartyFile)
            report.set(layout.buildDirectory.file("reports/legal/jar-legal-files.txt"))
        }
        tasks.named("check") { dependsOn(verifyJarLegalFiles) }
    }

    // -----------------------------------------------------------------------------------
    // Security floors for transitive dependencies (Trivy CRITICAL/HIGH backlog).
    //
    // Floors, not pins: `require` raises anything resolved BELOW the floor and leaves
    // anything already at or above it alone, so a later platform bump is never held back.
    // Every entry names the CVEs it clears, so the next person can delete an entry, rerun
    // `scripts/scan-image.sh`, and drop it for good if the CVE does not come back.
    //
    // Nothing managed by the Quarkus BOM belongs here: every module applies that BOM with
    // `enforcedPlatform`, whose forced versions beat a plain constraint outright. Those
    // coordinates go through `securityFloor` in the resolutionStrategy block below instead.
    // -----------------------------------------------------------------------------------
    pluginManager.withPlugin("java") {
        dependencies {
            constraints {
                // CVE-2026-40682, CVE-2026-42027 (both CRITICAL) and CVE-2026-42440 (HIGH).
                //
                // Arrives through quarkus-langchain4j-core -> langchain4j. Those versions
                // follow the platform member BOM in gradle/libs.versions.toml; opennlp is
                // a leaf transitive outside the enforced BOMs, so a constraint is effective.
                //
                // 2.5.11 is not a guess: upstream langchain4j made this exact in-place bump,
                // 2.5.4 -> 2.5.9 in 1.15.1 and -> 2.5.11 by 1.20.0, with no change to its own
                // opennlp call sites. It stays inside the 2.5.x patch line.
                //
                // Karyo never executes this code at all: opennlp is reached only through
                // `DocumentBySentenceSplitter`, part of langchain4j's document-splitting/RAG
                // surface, and Karyo's copilot uses only AiServices and @Tool (there is no RAG
                // in v1). The bump is about what ships inside the image, not about a live path.
                add("implementation", "org.apache.opennlp:opennlp-tools") {
                    version { require("2.5.11") }
                }
            }
        }
    }

    // -----------------------------------------------------------------------------------
    // Security floors for BOM-MANAGED transitives (Trivy CRITICAL/HIGH backlog).
    //
    // Why this is not a `constraints` block like the one above: every module applies the
    // Quarkus BOM with `enforcedPlatform`, which contributes FORCED versions. A forced
    // version beats a constraint, so a constraint on netty or jackson resolves to exactly
    // nothing and does so silently. `eachDependency` runs after conflict resolution and is
    // the one hook that can still move the selected version.
    //
    // `securityFloor` compares numerically and only ever raises, so this stays a floor and
    // never a pin: when the Quarkus platform moves past one of these on its own, the entry
    // becomes a no-op rather than a silent DOWNGRADE, which is what a bare `useVersion`
    // would have become. Qualifiers (".Final", "-rc1") are ignored by the comparison; every
    // coordinate here is a release line where that is exactly right.
    //
    // Whole families move together, never single artifacts. Jackson and Netty ship in
    // lockstep and mixing 2.19.2 `jackson-annotations` with 2.21.4 `jackson-databind`, or
    // 4.1.121 `netty-common` with 4.1.136 `netty-codec-http`, is its own class of runtime
    // failure - so the floors match by group, not by the individual CVE-bearing artifact.
    //
    // Each entry names what it clears. To retire one: delete it, run
    // `./scripts/scan-image.sh`, and if the CVEs do not come back the platform has caught
    // up and the floor was dead weight.
    // -----------------------------------------------------------------------------------
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            when {
                // 20 HIGH across netty-codec, -codec-dns, -codec-haproxy, -codec-http,
                // -codec-http2, -handler and -resolver-dns, originally resolved at
                // 4.1.121.Final. 4.1.136.Final is the highest floor those findings ask for
                // (CVE-2026-55831/55833/56745/56819/59901/55851). A newer platform's
                // 4.2.x line remains untouched by this upward-only floor.
                //
                // netty-tcnative is excluded on purpose: it lives in the io.netty group but
                // versions independently (2.0.x), so a group-wide floor would demand a
                // netty-tcnative 4.1.136 that has never existed. It is not on the classpath
                // today; the guard is here so that adding it later fails no build.
                requested.group == "io.netty" &&
                    !requested.name.startsWith("netty-tcnative") ->
                    securityFloor("4.1.136.Final")

                // jackson-annotations is NOT on the 2.21.4 line and must be floored
                // separately. Jackson releases it on a minor-only cadence - 2.19.x, then
                // 2.20, then 2.21, with no patch releases - because annotations rarely
                // change, while jackson-core and jackson-databind do get patch releases.
                // A single family-wide floor demands a jackson-annotations 2.21.4 that has
                // never been published and the whole configuration fails to resolve
                // ("2.19.2 -> 2.21.4 FAILED"). The BOM knows this mapping; eachDependency
                // does not, so it is spelled out. Check this before adding a family floor
                // for any other multi-artifact project.
                requested.group == "com.fasterxml.jackson.core" &&
                    requested.name == "jackson-annotations" ->
                    securityFloor("2.21")

                // GHSA-r7wm-3cxj-wff9 (jackson-core), CVE-2026-54512 and CVE-2026-54513
                // (jackson-databind), all at 2.19.2. 2.21.4 is the fix and is what the
                // Quarkus 3.36 BOM ships, so the combination is not novel. Applied across
                // the whole family (core, databind, datatype-*, module-*, jackson-bom)
                // because mixing Jackson artifact versions is its own failure mode.
                requested.group.startsWith("com.fasterxml.jackson") ->
                    securityFloor("2.21.4")

                // CVE-2026-40984, micrometer-core 1.14.7. 1.15.12 is the lower of the two
                // fixed lines Trivy offers; 1.16.x is a larger step for no extra coverage.
                //
                // Same shape of exclusion as netty-tcnative above: the io.micrometer group
                // is not one release line. `context-propagation` ships on 1.1.x and the
                // `micrometer-tracing*` artifacts on 1.5.x, both independently of
                // micrometer-core's 1.15.x, so a group-wide floor would demand versions of
                // them that have never been published and fail the whole configuration
                // ("1.4.7 -> 1.15.12 FAILED"). Neither is on the classpath today; the guard
                // is here so that the OTel/tracing bridge pulling one in later fails no
                // build. Everything else in the group tracks micrometer-core.
                requested.group == "io.micrometer" &&
                    requested.name != "context-propagation" &&
                    !requested.name.startsWith("micrometer-tracing") ->
                    securityFloor("1.15.12")

                // CVE-2026-42198 (fixed 42.7.11) and CVE-2026-54291 (fixed 42.7.12), JDBC
                // driver at 42.7.7. Patch line, no wire-protocol or API change.
                requested.group == "org.postgresql" && requested.name == "postgresql" ->
                    securityFloor("42.7.12")
            }
        }
    }

    pluginManager.withPlugin("org.jetbrains.kotlin.jvm") {
        apply(plugin = "dev.detekt")
        extensions.configure<dev.detekt.gradle.extensions.DetektExtension> {
            buildUponDefaultConfig = true
            config.setFrom(rootProject.files("config/detekt/detekt.yml"))
            basePath.set(rootProject.projectDir)
        }

        apply(plugin = "org.owasp.dependencycheck")
        extensions.configure<org.owasp.dependencycheck.gradle.extension.DependencyCheckExtension> {
            failBuildOnCVSS = 7.0f
            formats = listOf("HTML", "JSON")
            suppressionFile = rootProject.file("config/owasp/suppressions.xml").absolutePath
            nvd.apiKey = System.getenv("NVD_API_KEY") ?: ""
        }
    }
}

gradle.projectsEvaluated {
    val missingTestTasks = subprojects
        .filter { project ->
            val sourceRoot = project.file("src/test")
            sourceRoot.isDirectory && sourceRoot.walkTopDown().any { it.isFile }
        }
        .filter { it.tasks.findByName(deterministicTestTaskName) !is Test }
        .map { it.path }
    if (missingTestTasks.isNotEmpty()) {
        throw GradleException(
            "Projects with deterministic test sources lack a test task: ${missingTestTasks.joinToString()}"
        )
    }
}

gradle.taskGraph.whenReady {
    val violations = allTasks
        .filterIsInstance<Test>()
        .filter { it.name == deterministicTestTaskName }
        .flatMap { task ->
            buildList {
                if (!task.enabled) add("${task.path} is disabled")
                if (!task.onlyIf.isSatisfiedBy(task)) add("${task.path} is conditionally skipped")
                if (!task.isScanForTestClasses) add("${task.path} disables test-class scanning")
                if (task.dryRun.getOrElse(false)) add("${task.path} is a dry run")
                if (task.ignoreFailures) add("${task.path} ignores failures")
                if (!task.failOnNoDiscoveredTests.getOrElse(true)) {
                    add("${task.path} permits no discovered tests")
                }
                if (!task.filter.isFailOnNoMatchingTests) {
                    add("${task.path} permits no matching tests")
                }
                val testSourceSet = task.project.extensions
                    .findByType<SourceSetContainer>()
                    ?.findByName("test")
                val expectedClasses = testSourceSet?.output?.classesDirs?.files
                if (expectedClasses == null || task.testClassesDirs.files != expectedClasses) {
                    add("${task.path} does not execute the complete test source-set output")
                }
                val configuredSources = testSourceSet?.allSource?.files.orEmpty()
                    .map { it.canonicalFile }
                    .toSet()
                val omittedSources = task.project.fileTree("src/test") {
                    include("**/*.java", "**/*.kt")
                }.files
                    .map { it.canonicalFile }
                    .filterNot { it in configuredSources }
                if (omittedSources.isNotEmpty()) {
                    add("${task.path} omits test sources from its source set")
                }
                if (task.includes.isNotEmpty()) add("${task.path} has task include filters")
                if (task.excludes.isNotEmpty()) add("${task.path} has task exclude filters")
                if (task.filter.includePatterns.isNotEmpty()) {
                    add("${task.path} has configured test include filters")
                }
                if (task.filter.excludePatterns.isNotEmpty()) {
                    add("${task.path} has configured test exclude filters")
                }
                val options = task.options as? JUnitPlatformOptions
                if (options == null) {
                    add("${task.path} does not use JUnit Platform")
                } else {
                    if (options.includeEngines.isNotEmpty()) add("${task.path} filters JUnit engines")
                    if (options.excludeEngines.isNotEmpty()) add("${task.path} excludes JUnit engines")
                    if (options.includeTags.isNotEmpty()) add("${task.path} filters JUnit tags")
                    if (options.excludeTags.isNotEmpty()) add("${task.path} excludes JUnit tags")
                }
            }
        }
    if (violations.isNotEmpty()) {
        throw GradleException(
            "Deterministic Gradle test contract violated:\n${violations.joinToString("\n") { "- $it" }}"
        )
    }
}


/**
 * Raises this dependency to [floor] if it currently resolves BELOW it, and does nothing
 * otherwise. Used by the security-floor block in `subprojects` to lift transitives that the
 * Quarkus BOM pins below a published CVE fix, without pinning them down again once the
 * platform catches up.
 *
 * Comparison is over the numeric runs in each version, left to right, missing segments
 * treated as 0: `4.1.121.Final` < `4.1.136.Final`, and `4.2.13.Final` is NOT below
 * `4.1.136.Final` so a newer major/minor is never dragged backwards. Qualifiers are ignored,
 * which is correct for every coordinate that uses this and would not be for a pre-release
 * line - do not reach for it there.
 */
fun DependencyResolveDetails.securityFloor(floor: String) {
    if (versionIsBelow(requested.version.orEmpty(), floor)) {
        useVersion(floor)
        because("security floor: see the CVE note in the subprojects block of build.gradle.kts")
    }
}

fun versionIsBelow(current: String, floor: String): Boolean {
    val digits = Regex("\\d+")
    val currentParts = digits.findAll(current).map { it.value.toLong() }.toList()
    val floorParts = digits.findAll(floor).map { it.value.toLong() }.toList()
    if (currentParts.isEmpty()) return false
    for (i in 0 until maxOf(currentParts.size, floorParts.size)) {
        val a = currentParts.getOrElse(i) { 0L }
        val b = floorParts.getOrElse(i) { 0L }
        if (a != b) return a < b
    }
    return false
}
