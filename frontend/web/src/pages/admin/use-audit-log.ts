import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import type { JournalEntry } from '@/features/insights/use-journals';

/**
 * All tenant inventory-journal rows (B17 audit log), newest-first — no
 * `location` filter, unlike `useJournals`/`useOrderActivity`. Reuses the
 * same `JournalEntry` shape (and `recordTypeMeta`/`signedDelta` helpers)
 * that Locations' movement timeline and Inventory's ledger already render.
 */
export function useAuditLog() {
  return useQuery({
    queryKey: ['journals', 'audit-log'],
    queryFn: () => api.get<JournalEntry[]>('/api/v1/journals'),
    staleTime: 30_000,
  });
}
