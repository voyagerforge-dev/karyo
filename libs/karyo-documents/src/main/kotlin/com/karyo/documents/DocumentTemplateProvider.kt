package com.karyo.documents

/**
 * First-non-null template-source override, consulted by [DocumentRenderer] before the bundled
 * classpath template. Implemented by `karyo-docstore` ([ownerClientId] resolves to a licensed
 * per-client override there); resolved via `Instance<DocumentTemplateProvider>` so [DocumentRenderer]
 * has no compile-time dependency on any module that implements it.
 *
 * [ownerClientId] is the rendered entity's owner (the branded client) — null means "no owner
 * context" and MUST resolve to no override (never fall back to some other client's template).
 */
interface DocumentTemplateProvider {
    fun templateSource(templatePath: String, ownerClientId: Long?): String?
}
