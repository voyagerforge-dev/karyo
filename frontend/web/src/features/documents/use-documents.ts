import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api, downloadDocument } from '@/lib/api-client';
import type { PaginatedResponse } from '@/types/api';
import type { DocumentFilters, StoredDocumentResponse } from '@/types/document';

const DOCUMENTS_KEY = 'documents';

/** Builds the `GET /api/v1/documents` query string from the filter/paging bag. */
function documentsUrl(filters: DocumentFilters): string {
  const params = new URLSearchParams();
  if (filters.entityType) params.set('entityType', filters.entityType);
  if (filters.entityId != null) params.set('entityId', String(filters.entityId));
  if (filters.documentType) params.set('documentType', filters.documentType);
  params.set('page', String(filters.page));
  params.set('size', String(filters.size));
  return `/api/v1/documents?${params.toString()}`;
}

/**
 * The D13 documents archive list — `GET /api/v1/documents`, paginated, filterable by
 * entityType/entityId/documentType. Query key includes the whole filter bag so any filter
 * change (or page turn) refetches.
 */
export function useDocuments(filters: DocumentFilters) {
  return useQuery({
    queryKey: [DOCUMENTS_KEY, filters],
    queryFn: () => api.get<PaginatedResponse<StoredDocumentResponse>>(documentsUrl(filters)),
  });
}

/** `DELETE /api/v1/documents/{id}` — MANAGER+ server-side; invalidates the archive list. */
export function useDeleteDocument() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/api/v1/documents/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DOCUMENTS_KEY] }),
  });
}

/**
 * Download an archived document's bytes (`GET /{id}/content`) and save as `fileName` --
 * mirrors `lib/document-actions.ts`'s saveCsv/saveZpl blob-save pattern, but lives here
 * (not lib/document-actions.ts) since it operates on an archive row id, not a live
 * document's own generating URL.
 */
export async function downloadArchivedDocument(id: number, fileName: string): Promise<void> {
  const blob = await downloadDocument(`/api/v1/documents/${id}/content`);
  const objectUrl = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = objectUrl;
  a.download = fileName;
  a.click();
  URL.revokeObjectURL(objectUrl);
}
