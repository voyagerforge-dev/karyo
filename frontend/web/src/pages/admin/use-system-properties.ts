import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { SystemPropertyView, UpsertSystemPropertyRequest } from '@/types/system-property';

const SYSTEM_PROPERTIES_KEY = ['system-properties'];

/** GET /api/v1/system-properties — user-admin. Effective view: catalog + stored extras. */
export function useSystemProperties() {
  return useQuery({
    queryKey: SYSTEM_PROPERTIES_KEY,
    queryFn: () => api.get<SystemPropertyView[]>('/api/v1/system-properties'),
  });
}

/** PUT /api/v1/system-properties/{key} — 403s (via api-client toast) for an owner-forbidden key. */
export function useSetSystemProperty() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ key, body }: { key: string; body: UpsertSystemPropertyRequest }) =>
      api.put<SystemPropertyView>(`/api/v1/system-properties/${encodeURIComponent(key)}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: SYSTEM_PROPERTIES_KEY }),
  });
}

/** DELETE /api/v1/system-properties/{key} — reverts to the fallback (CONFIG/DEFAULT) value. */
export function useResetSystemProperty() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: ({ key, context }: { key: string; context?: string }) => {
      const qs = context ? `?context=${encodeURIComponent(context)}` : '';
      return api.delete<void>(`/api/v1/system-properties/${encodeURIComponent(key)}${qs}`);
    },
    onSuccess: () => qc.invalidateQueries({ queryKey: SYSTEM_PROPERTIES_KEY }),
  });
}
