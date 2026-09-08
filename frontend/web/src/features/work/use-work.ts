import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { WorkItemResponse } from '@/types/work';

const WORK_KEY = ['work'];

/** GET /api/v1/work/available — optional type filter, staleTime 5s */
export function useAvailableWork(type?: string) {
  const url = type ? `/api/v1/work/available?type=${type}` : '/api/v1/work/available';
  return useQuery({
    queryKey: [...WORK_KEY, 'available', { type }],
    queryFn: () => api.get<WorkItemResponse[]>(url),
    staleTime: 5000,
  });
}

/** GET /api/v1/work/mine — my claimed work */
export function useMyWork() {
  return useQuery({
    queryKey: [...WORK_KEY, 'mine'],
    queryFn: () => api.get<WorkItemResponse[]>('/api/v1/work/mine'),
  });
}

/** POST /api/v1/work/{ref}/claim — claim a work item */
export function useClaimWork() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ref: string) =>
      api.post<WorkItemResponse>(`/api/v1/work/${encodeURIComponent(ref)}/claim`, {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: WORK_KEY });
      qc.invalidateQueries({ queryKey: ['transport-orders'] });
      qc.invalidateQueries({ queryKey: ['pick-orders'] });
    },
  });
}

/** POST /api/v1/work/{ref}/release — release a claimed work item */
export function useReleaseWork() {
  const qc = useQueryClient();
  return useMutation({
    mutationFn: (ref: string) =>
      // release returns 204 — no body (claim is the one that returns the item)
      api.post<void>(`/api/v1/work/${encodeURIComponent(ref)}/release`, {}),
    onSuccess: () => {
      qc.invalidateQueries({ queryKey: WORK_KEY });
      qc.invalidateQueries({ queryKey: ['transport-orders'] });
      qc.invalidateQueries({ queryKey: ['pick-orders'] });
    },
  });
}
