import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type {
  ClientConsistencyReport,
  ClientResponse,
  CreateClientRequest,
  UpdateClientRequest,
} from '@/types/client';

const CLIENTS_KEY = ['clients'];

/** GET /api/v1/clients — VIEWER+, tenant-scoped server-side. Bare array (no pagination envelope). */
export function useClients() {
  return useQuery({
    queryKey: CLIENTS_KEY,
    queryFn: () => api.get<ClientResponse[]>('/api/v1/clients'),
  });
}

export function useCreateClient() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: CreateClientRequest) => api.post<ClientResponse>('/api/v1/clients', body),
    onSuccess: () => qc.invalidateQueries({ queryKey: CLIENTS_KEY }),
  });
}

export function useUpdateClient(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (body: UpdateClientRequest) => api.put<ClientResponse>(`/api/v1/clients/${id}`, body),
    onSuccess: () => qc.invalidateQueries({ queryKey: CLIENTS_KEY }),
  });
}

/** POST .../reactivate when active=true, .../deactivate when active=false — no DELETE exists. */
export function useSetClientActive(id: number) {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (active: boolean) =>
      api.post<ClientResponse>(`/api/v1/clients/${id}/${active ? 'reactivate' : 'deactivate'}`, {}),
    onSuccess: () => qc.invalidateQueries({ queryKey: CLIENTS_KEY }),
  });
}

/** On-demand — user-admin only; call via refetch from the button. */
export function useConsistencyCheck() {
  return useQuery({
    queryKey: ['clients', 'consistency'],
    queryFn: () => api.get<ClientConsistencyReport>('/api/v1/clients/consistency'),
    enabled: false,
  });
}
