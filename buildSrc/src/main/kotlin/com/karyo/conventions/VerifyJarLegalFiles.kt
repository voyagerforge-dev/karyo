package com.karyo.conventions

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.zip.ZipFile

/**
 * Fails the build unless every jar this project publishes carries `META-INF/NOTICE`,
 * `META-INF/LICENSE` and `META-INF/THIRD-PARTY-NOTICES`, byte-identical to the sources they
 * were copied from.
 *
 * Apache-2.0 section 4(d) requires the NOTICE to accompany every distribution, and Karyo's
 * NOTICE is also the file that names the nine commercial `-core` modules as outside the
 * Apache grant. A jar that ships without it is a distribution that never states its own
 * licence boundary, which is exactly the failure this task exists to make impossible. The
 * third-party inventory rides along because the licence label deliberately does not
 * enumerate the LGPL-2.1, EPL-2.0 and SIL-OFL-1.1 components a distribution contains.
 *
 * Presence alone is not enough to check. The `metaInf` copy in the root build is silent when
 * its source file is missing - a rename of NOTICE would empty every jar and no task would
 * fail - so this compares the packaged bytes against the file on disk.
 *
 * What that proves is bounded by the same convention: this task is registered beside the
 * `metaInf` copy, over the same live `Jar` task collection, so it covers exactly the jars
 * the copy covers. For each of those it proves all three files are present and byte-identical
 * to their sources, which catches a copy that silently produced nothing and drift between the
 * packaged bytes and the source. A module the convention never reaches registers no task
 * here either, and is not covered.
 */
@CacheableTask
abstract class VerifyJarLegalFiles : DefaultTask() {

    /** Every jar produced by this project. Wiring a task collection here carries the dependency. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.NAME_ONLY)
    abstract val jars: ConfigurableFileCollection

    /** The repository NOTICE, identical for every module. */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val notice: RegularFileProperty

    /**
     * The licence text this module ships: its own `LICENSE` marker for a commercial `-core`,
     * the Apache-2.0 root `LICENSE` for everything else. The licence boundary is per Gradle
     * module, so stamping the Apache text onto a proprietary jar would be a misstatement.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val license: RegularFileProperty

    /**
     * The root third-party inventory, identical for every module: no module carries one of
     * its own, and it is what discloses the terms the licence label does not enumerate.
     */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val thirdPartyNotices: RegularFileProperty

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun verify() {
        val expected = mapOf(
            "META-INF/NOTICE" to notice.get().asFile,
            "META-INF/LICENSE" to license.get().asFile,
            "META-INF/THIRD-PARTY-NOTICES" to thirdPartyNotices.get().asFile,
        )
        val failures = mutableListOf<String>()
        val verified = mutableListOf<String>()

        for (jar in jars.files.filter { it.isFile }) {
            ZipFile(jar).use { zip ->
                for ((entryName, source) in expected) {
                    val entry = zip.getEntry(entryName)
                    when {
                        entry == null ->
                            failures += "${jar.name} is missing $entryName"
                        !zip.getInputStream(entry).use { it.readBytes() }
                            .contentEquals(source.readBytes()) ->
                            failures += "${jar.name} $entryName does not match ${source.path}"
                    }
                }
            }
            verified += "${jar.name}: ${expected.keys.joinToString(", ")}"
        }

        if (failures.isNotEmpty()) {
            throw GradleException(
                "Distributed jars must carry the licence notices (Apache-2.0 section 4(d)):\n" +
                    failures.joinToString("\n") { "- $it" }
            )
        }

        val output = report.get().asFile
        output.parentFile.mkdirs()
        output.writeText(
            if (verified.isEmpty()) {
                "no jars produced\n"
            } else {
                verified.sorted().joinToString("\n", postfix = "\n")
            }
        )
    }
}
