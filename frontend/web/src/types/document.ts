/**
 * Documents archive DTOs (D13, docstore-templates sprint) — mirrors the backend's
 * StoredDocumentResponse.
 * @see services/document-service/karyo-docstore-api/src/main/kotlin/com/karyo/docstore/dto/DocumentDtos.kt
 * Deliberately excludes `content` — bytes are served separately via `GET /{id}/content`,
 * mirroring how the 10 live document endpoints stream bytes rather than embedding them in JSON.
 */
export interface StoredDocumentResponse {
  id: number;
  entityType: string;
  entityId: number;
  documentType: string;
  fileName: string;
  mediaType: string;
  sizeBytes: number;
  created: string;
}

/** The 6 entityType values the 10 archivable document endpoints attach to. */
export const DOCUMENT_ENTITY_TYPES = [
  'shipment',
  'shipping-unit',
  'pick-order',
  'delivery-order',
  'unit-load',
  'location',
] as const;
export type DocumentEntityType = (typeof DOCUMENT_ENTITY_TYPES)[number];

/** The 7 documentType values across the 10 archivable document endpoints. */
export const DOCUMENT_TYPES = [
  'bol',
  'packing-slip',
  'packet-list',
  'label',
  'content-list',
  'pick-ticket',
  'delivery-note',
] as const;
export type DocumentType = (typeof DOCUMENT_TYPES)[number];

/** Filters + paging accepted by `GET /api/v1/documents`. */
export interface DocumentFilters {
  entityType?: string;
  entityId?: number;
  documentType?: string;
  page: number;
  size: number;
}

/**
 * Per-client template-override version (D14, `documents` paid engine). Mirrors
 * `DocumentTemplateResponse` — INCLUDES `content` (unlike `StoredDocumentResponse` above): a
 * template is Qute source text, not a bytes-heavy binary, so the upload/version UI can show it
 * directly.
 * @see services/document-service/karyo-docstore-api/src/main/kotlin/com/karyo/docstore/dto/DocumentDtos.kt
 */
export interface DocumentTemplateResponse {
  id: number;
  clientId: number;
  templatePath: string;
  templateVersion: number;
  active: boolean;
  content: string;
  created: string;
  modified: string;
}

/** `POST /api/v1/document-templates` body. */
export interface CreateDocumentTemplateRequest {
  clientId: number;
  templatePath: string;
  content: string;
}

/**
 * The 10 classpath template paths overridable via the `documents` paid engine — copied verbatim
 * from the backend source of truth,
 * `DocumentTemplateService.TEMPLATE_WHITELIST` (services/document-service/karyo-docstore-core/
 * src/main/kotlin/com/karyo/docstore/service/DocumentTemplateService.kt). An upload targeting any
 * other path is refused server-side with 400 `PathNotWhitelisted` — this list is FE-side
 * guidance (a picker), not an independent source of truth; keep it in sync with the backend
 * constant whenever a new document template ships.
 */
export const TEMPLATE_PATH_WHITELIST = [
  '/templates/packing-slip.html',
  '/templates/bol.html',
  '/templates/label.zpl',
  '/templates/packet-list.html',
  '/templates/packet-content-list.html',
  '/templates/pick-ticket.html',
  '/templates/delivery-note.html',
  '/templates/ul-content-list.html',
  '/templates/ul-label.zpl',
  '/templates/location-label.zpl',
] as const;
export type TemplatePath = (typeof TEMPLATE_PATH_WHITELIST)[number];
