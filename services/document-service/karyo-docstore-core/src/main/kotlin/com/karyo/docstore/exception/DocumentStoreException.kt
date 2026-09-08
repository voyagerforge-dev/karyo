package com.karyo.docstore.exception

import com.karyo.common.exception.KaryoException

sealed class DocumentStoreException(message: String) : KaryoException(message) {
    class NotFound(id: Long) : DocumentStoreException("Document $id not found")

    class ContentTooLarge(actualBytes: Int, maxBytes: Int) :
        DocumentStoreException("Document content is $actualBytes bytes, exceeding the $maxBytes byte limit")

    /**
     * An OWNER principal attempted to store a document under a different owner's `clientId`.
     * No existing row to leak (unlike [NotFound]'s 404), so this is a plain 403 — mirrors
     * [com.karyo.auth.exception.AuthException.ClientAdministrationForbidden]'s reasoning.
     */
    class Forbidden(ownerClientId: Long) :
        DocumentStoreException("Not permitted to store a document under client $ownerClientId")
}
