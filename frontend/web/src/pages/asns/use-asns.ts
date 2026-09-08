import { useMutation, useQueries, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import { sortingStateToString } from '@/lib/table-utils';
import type { PaginatedResponse } from '@/types/api';
import type {
  AsnResponse,
  AsnFinishResponse,
  CreateAsnRequest,
  UpdateAsnRequest,
  CreateUlAdviceRequest,
  UlAdviceResponse,
} from '@/types/receiving';

export { sortingStateToString };

interface UseListOptions {
  page: number;
  size: number;
  sort?: string;
  /** OrderState code filter (server-side ?state=) */
  state?: number;
  /** Free-text search over asnNumber/externalNumber/carrier (server-side ?q=) */
  q?: string;
}

/** Fetch paginated ASNs with server-side sort/pagination/filter/search. */
export function useAsns(options: UseListOptions) {
  const { page, size, sort, state, q } = options;
  return useQuery({
    queryKey: ['asns', { page, size, sort, state, q }],
    queryFn: () => {
      const params = new URLSearchParams({ page: String(page), size: String(size) });
      if (sort) params.set('sort', sort);
      if (state != null) params.set('state', String(state));
      if (q) params.set('q', q);
      return api.get<PaginatedResponse<AsnResponse>>(`/api/v1/asns?${params.toString()}`);
    },
    staleTime: 10_000,
  });
}

/** Fetch a single ASN (lines incl. received/remaining/progress). */
export function useAsn(id: number | undefined) {
  return useQuery({
    queryKey: ['asns', 'detail', id],
    queryFn: () => api.get<AsnResponse>(`/api/v1/asns/${id}`),
    enabled: id != null,
    staleTime: 5_000,
  });
}

/**
 * Fetch multiple ASNs by id (the receiving workbench merges expected lines
 * across every ASN linked to a receipt, V424 M2M). Shares the same query key
 * as `useAsn` so single-ASN and multi-ASN callers hit the same cache entry.
 */
export function useAsnsByIds(ids: number[]) {
  const results = useQueries({
    queries: ids.map((id) => ({
      queryKey: ['asns', 'detail', id],
      queryFn: () => api.get<AsnResponse>(`/api/v1/asns/${id}`),
      staleTime: 5_000,
    })),
  });
  const data = results
    .map((r) => r.data)
    .filter((asn): asn is AsnResponse => asn != null);
  const isLoading = results.some((r) => r.isLoading);
  return { data, isLoading };
}

function useInvalidateAsns() {
  const queryClient = useQueryClient();
  return () => queryClient.invalidateQueries({ queryKey: ['asns'] });
}

/** Create a new ASN with expected lines. */
export function useCreateAsn() {
  const invalidate = useInvalidateAsns();
  return useMutation({
    mutationFn: (data: CreateAsnRequest) => api.post<AsnResponse>('/api/v1/asns', data),
    onSuccess: (asn) => {
      invalidate();
      toast.success(`ASN ${asn.asnNumber} created`);
    },
  });
}

/** Update ASN header fields (only allowed while CREATED; backend 409 otherwise). */
export function useUpdateAsn() {
  const invalidate = useInvalidateAsns();
  return useMutation({
    mutationFn: ({ id, ...data }: UpdateAsnRequest & { id: number }) =>
      api.put<AsnResponse>(`/api/v1/asns/${id}`, data),
    onSuccess: () => {
      invalidate();
      toast.success('ASN updated');
    },
  });
}

/** Release a CREATED ASN — it becomes receivable. */
export function useReleaseAsn() {
  const invalidate = useInvalidateAsns();
  return useMutation({
    mutationFn: (id: number) => api.post<AsnResponse>(`/api/v1/asns/${id}/release`, {}),
    onSuccess: (asn) => {
      invalidate();
      toast.success(`ASN ${asn.asnNumber} released`);
    },
  });
}

/** Cancel an ASN (pre-STARTED only). */
export function useCancelAsn() {
  const invalidate = useInvalidateAsns();
  return useMutation({
    mutationFn: (id: number) => api.post<AsnResponse>(`/api/v1/asns/${id}/cancel`, {}),
    onSuccess: (asn) => {
      invalidate();
      toast.success(`ASN ${asn.asnNumber} canceled`);
    },
  });
}

/**
 * Force-finish a RELEASED/STARTED ASN. The response carries the shortage
 * summary (short lines closed below expectation); empty = fully received.
 */
export function useFinishAsn() {
  const invalidate = useInvalidateAsns();
  return useMutation({
    mutationFn: (id: number) => api.post<AsnFinishResponse>(`/api/v1/asns/${id}/finish`, {}),
    onSuccess: ({ asn, shortages }) => {
      invalidate();
      if (shortages.length > 0) {
        toast.warning(`ASN ${asn.asnNumber} finished with ${shortages.length} short line(s)`);
      } else {
        toast.success(`ASN ${asn.asnNumber} finished — fully received`);
      }
    },
  });
}

/** Register a UL pre-advice on an ASN (CREATED/RELEASED only; backend enforces). */
export function useCreateUlAdvice() {
  const invalidate = useInvalidateAsns();
  return useMutation({
    mutationFn: ({ asnId, ...data }: CreateUlAdviceRequest & { asnId: number }) =>
      api.post<UlAdviceResponse>(`/api/v1/asns/${asnId}/ul-advices`, data),
    onSuccess: (advice) => {
      invalidate();
      toast.success(`Pre-advice ${advice.labelId} registered`);
    },
  });
}

/** Remove a UL pre-advice from an ASN. */
export function useDeleteUlAdvice() {
  const invalidate = useInvalidateAsns();
  return useMutation({
    mutationFn: ({ asnId, adviceId }: { asnId: number; adviceId: number }) =>
      api.delete<void>(`/api/v1/asns/${asnId}/ul-advices/${adviceId}`),
    onSuccess: () => {
      invalidate();
      toast.success('Pre-advice removed');
    },
  });
}
