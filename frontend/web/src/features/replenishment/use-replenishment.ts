import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { toast } from 'sonner';
import { api } from '@/lib/api-client';
import type { ReplenishmentNeed, ReplenishmentScanResult } from '@/types/replenishment';

/**
 * Fetch the current replenishment needs list — fixed-location assignments that are
 * at or below their minimum quantity. Plain array (not paginated).
 */
export function useReplenishmentNeeds() {
  return useQuery({
    queryKey: ['replenishment-needs'],
    queryFn: () => api.get<ReplenishmentNeed[]>('/api/v1/replenishment/needs'),
    staleTime: 15_000,
  });
}

function useInvalidateReplenishment() {
  const queryClient = useQueryClient();
  return () => {
    void queryClient.invalidateQueries({ queryKey: ['replenishment-needs'] });
    // Scan generates REPLENISH transport orders — keep both the legacy
    // transport-orders list AND the unified work inbox (/tasks reads
    // ['work', ...]) fresh so the generated tasks appear without a remount.
    void queryClient.invalidateQueries({ queryKey: ['transport-orders'] });
    void queryClient.invalidateQueries({ queryKey: ['work'] });
  };
}

/**
 * Trigger a replenishment scan: the backend inspects every fixed-location assignment
 * and attempts to generate one REPLENISH transport order per open need.
 * Returns { generated, shortfalls } — generated tasks + any locations where no
 * source stock was available.
 */
export function useScanReplenishment() {
  const invalidate = useInvalidateReplenishment();
  return useMutation({
    mutationFn: () => api.post<ReplenishmentScanResult>('/api/v1/replenishment/scan', {}),
    onSuccess: (result) => {
      invalidate();
      const { generated, shortfalls } = result;
      if (generated.length > 0 && shortfalls.length === 0) {
        toast.success(`Scan complete — ${generated.length} task${generated.length === 1 ? '' : 's'} generated`);
      } else if (generated.length > 0 && shortfalls.length > 0) {
        toast.warning(
          `Scan complete — ${generated.length} task${generated.length === 1 ? '' : 's'} generated, ${shortfalls.length} location${shortfalls.length === 1 ? '' : 's'} have no source stock`,
        );
      } else if (shortfalls.length > 0) {
        toast.warning(
          `Scan complete — no tasks generated, ${shortfalls.length} location${shortfalls.length === 1 ? '' : 's'} have no source stock`,
        );
      } else {
        toast.success('Scan complete — no replenishment needed');
      }
    },
  });
}
