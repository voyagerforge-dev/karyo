import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { CreateDocumentTemplateRequest, DocumentTemplateResponse } from '@/types/document';

const DOCUMENT_TEMPLATES_KEY = 'document-templates';

/**
 * D14 per-client template-override versions — `GET /api/v1/document-templates` (`user-admin` +
 * `documents` license server-side). Both `clientId` and `templatePath` are required by the
 * backend (400 otherwise), so the query stays disabled until both are chosen — mirrors
 * `useConsistencyCheck`'s on-demand pattern, but gated on args rather than a manual `refetch`.
 */
export function useDocumentTemplates(clientId: number | undefined, templatePath: string | undefined) {
  return useQuery({
    queryKey: [DOCUMENT_TEMPLATES_KEY, clientId, templatePath],
    queryFn: () => {
      const params = new URLSearchParams({
        clientId: String(clientId),
        templatePath: templatePath ?? '',
      });
      return api.get<DocumentTemplateResponse[]>(`/api/v1/document-templates?${params.toString()}`);
    },
    enabled: clientId != null && !!templatePath,
  });
}

/** `POST /api/v1/document-templates` — uploads a new version, deactivating any sibling active row. */
export function useUploadDocumentTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateDocumentTemplateRequest) =>
      api.post<DocumentTemplateResponse>('/api/v1/document-templates', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DOCUMENT_TEMPLATES_KEY] }),
  });
}

/** `POST /api/v1/document-templates/{id}/activate` — makes this version the active one. */
export function useActivateDocumentTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) =>
      api.post<DocumentTemplateResponse>(`/api/v1/document-templates/${id}/activate`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DOCUMENT_TEMPLATES_KEY] }),
  });
}

/** `DELETE /api/v1/document-templates/{id}` — removes a version (active or not). */
export function useDeleteDocumentTemplate() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.delete<void>(`/api/v1/document-templates/${id}`),
    onSuccess: () => qc.invalidateQueries({ queryKey: [DOCUMENT_TEMPLATES_KEY] }),
  });
}
