package com.karyo.documents

import com.openhtmltopdf.extend.FSUriResolver
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder
import io.quarkus.qute.Engine
import io.quarkus.qute.HtmlEscaper
import io.quarkus.qute.Variant
import jakarta.enterprise.context.ApplicationScoped
import jakarta.enterprise.inject.Instance
import java.io.ByteArrayOutputStream

/**
 * [templateProviders] is the override seam (docstore-templates sprint, Task 3): resolved lazily
 * per [render] call via `Instance<DocumentTemplateProvider>` so this library has no compile-time
 * dependency on whichever module (karyo-docstore) implements it. When no implementation is on
 * the classpath (e.g. a lib-level unit test constructing this class directly) [Instance] iterates
 * empty and every render falls through to the bundled classpath template, unchanged from before
 * this seam existed.
 */
@ApplicationScoped
class DocumentRenderer(
    private val templateProviders: Instance<DocumentTemplateProvider>,
) {

    /**
     * Self-contained Qute engine with an [HtmlEscaper] result mapper registered for the
     * `text/html` content type. Escaping is variant-driven: only templates parsed with the
     * [Variant.TEXT_HTML] variant get interpolated values HTML-escaped, so `.zpl` (text) output
     * is left untouched. Built locally (not the injected Quarkus Engine) so escaping behaviour
     * does not depend on the app-wide engine config.
     */
    private val engine: Engine = Engine.builder()
        .addDefaults()
        .addResultMapper(HtmlEscaper(listOf(Variant.TEXT_HTML)))
        .build()

    /**
     * Render a Qute template (HTML or text) with [data] (Map; nested Maps/Lists for blocks).
     *
     * [ownerClientId] (docstore-templates sprint, Task 3) is the rendered entity's owner —
     * consulted FIRST against every registered [DocumentTemplateProvider] (first-non-null wins);
     * only when no provider returns an override does this fall back to the bundled classpath
     * template at [templatePath]. `null` (the default — every pre-existing caller, and any call
     * with no owner context) always skips the override chain, per [DocumentTemplateProvider]'s
     * contract.
     *
     * Security: `.html` templates are parsed with the `text/html` variant so the [HtmlEscaper]
     * escapes every interpolated value (defends against HTML/JS injection and SSRF-via-`<img>`
     * into the PDF renderer). `.zpl` templates have ZPL control chars (`^`, `~`) stripped from
     * input strings to prevent ZPL command injection. This treatment is keyed on [templatePath]'s
     * suffix alone, so an override template gets EXACTLY the same variant/escaping/sanitization
     * as the bundled one it replaces — an uploaded template is trusted-by-role code, not trusted
     * to skip the injection defenses.
     */
    fun render(templatePath: String, data: Map<String, Any?>, ownerClientId: Long? = null): String {
        val override = templateProviders.firstNotNullOfOrNull { it.templateSource(templatePath, ownerClientId) }
        val content = override
            ?: javaClass.getResourceAsStream(templatePath)?.bufferedReader()?.use { it.readText() }
            ?: error("Template not found: $templatePath")
        val template = parse(templatePath, content)
        val bindings = if (templatePath.endsWith(".zpl")) sanitizeZpl(data) else data
        var instance = template.instance()
        bindings.forEach { (k, v) -> instance = instance.data(k, v) }
        return instance.render()
    }

    /**
     * Parse-validates an uploaded template's [source] against the same variant [templatePath]'s
     * suffix selects at render time (Task 3's upload-time gate — an uploaded template is parsed
     * before it is ever persisted as active). Throws [IllegalArgumentException] wrapping Qute's
     * own parse-error message on malformed syntax; returns normally for a valid template.
     */
    fun validate(templatePath: String, source: String) {
        runCatching { parse(templatePath, source) }
            .onFailure { throw IllegalArgumentException(it.message ?: "Invalid template", it) }
    }

    private fun parse(templatePath: String, content: String) = if (templatePath.endsWith(".html")) {
        engine.parse(content, Variant.forContentType(Variant.TEXT_HTML), templatePath)
    } else {
        engine.parse(content)
    }

    /**
     * Convert an HTML string to PDF bytes (openhtmltopdf).
     *
     * Security: a no-op URI resolver blocks ALL sub-resource fetches. Our templates use inline CSS
     * and reference no external images/fonts, so resolving any URI is unnecessary — refusing it
     * neutralises SSRF (e.g. cloud-metadata endpoints) from injected markup that survived escaping.
     */
    fun htmlToPdf(html: String): ByteArray {
        val out = ByteArrayOutputStream()
        PdfRendererBuilder()
            .useFastMode()
            .useUriResolver(FSUriResolver { _, _ -> null })
            .withHtmlContent(html, null)
            .toStream(out)
            .run()
        return out.toByteArray()
    }

    /**
     * Strip ZPL control chars (`^`, `~`) from string values (incl. one level of nested Map values).
     *
     * Only flat values and one level of nested `Map`s are descended into — `List` values (e.g. a
     * future ZPL template with a row-loop over unsanitized strings) are passed through untouched.
     * Any future ZPL template that binds a `List<String>` or `List<Map<String, Any?>>` must
     * pre-sanitize those strings itself before handing the data to [render].
     */
    private fun sanitizeZpl(data: Map<String, Any?>): Map<String, Any?> = data.mapValues { (_, v) ->
        when (v) {
            is String -> v.stripZpl()
            is Map<*, *> -> v.mapValues { (_, nv) -> if (nv is String) nv.stripZpl() else nv }
            else -> v
        }
    }

    private fun String.stripZpl(): String = replace("^", "").replace("~", "")
}
