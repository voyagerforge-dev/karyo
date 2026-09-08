import { useQuery } from '@tanstack/react-query';
import { api } from '@/lib/api-client';

/** One inventory-journal row (mirrors the backend JournalEntryResponse). */
export interface JournalEntry {
  id?: number;
  recordType: number;
  recordTypeName: string;
  productNumber: string | null;
  /** Not fetched by the location-scoped hooks below (DTO field exists; B17 audit log uses it). */
  productName?: string | null;
  amount: number | null;
  fromStorageLocation: string | null;
  toStorageLocation: string | null;
  lotNumber: string | null;
  correlationId: string | null;
  /** Not fetched by the location-scoped hooks below (DTO field exists; B17 audit log uses it). */
  operatorName?: string | null;
  /** Not fetched by the location-scoped hooks below (DTO field exists; B17 audit log uses it). */
  activityCode?: string | null;
  /** Source IP of an auth event (record types 10-12, SC19); null on stock-movement rows. */
  ipAddress?: string | null;
  created: string;
}

/** Signed quantity delta at `location`: +amount when it's the destination,
 *  −amount when it's the source, 0 when neither (or amount missing). */
export function signedDelta(e: JournalEntry, location: string): number {
  const amt = e.amount ?? 0;
  if (e.toStorageLocation === location) return amt;
  if (e.fromStorageLocation === location) return -amt;
  return 0;
}

const META: Record<number, { label: string; dotHex: string }> = {
  1: { label: 'Receipt', dotHex: 'var(--info)' },
  3: { label: 'Pick', dotHex: 'var(--acc-color)' },
  5: { label: 'Transfer', dotHex: 'var(--acc-color)' },
  7: { label: 'Count adjust', dotHex: 'var(--warning-foreground)' },
  // Auth events (SC19) — distinct tones from the inventory-movement rows above.
  10: { label: 'Login', dotHex: 'var(--success-foreground)' },
  11: { label: 'Logout', dotHex: 'var(--muted-foreground)' },
  12: { label: 'Login failed', dotHex: 'var(--destructive)' },
};

/** Record type → display label + theme-aware dot color (CSS var string). */
export function recordTypeMeta(recordType: number): { label: string; dotHex: string } {
  return META[recordType] ?? { label: 'Adjust', dotHex: 'var(--warning-foreground)' };
}

/**
 * Real inventory-journal rows for a location (and optionally an item at that
 * location), newest-first. The endpoint returns a plain array.
 */
export function useJournals(opts: {
  location: string;
  productNumber?: string;
  enabled?: boolean;
}): { data: JournalEntry[]; isLoading: boolean } {
  const { location, productNumber, enabled = true } = opts;
  const { data, isLoading } = useQuery({
    queryKey: ['journals', { location, productNumber }],
    queryFn: () => {
      const params = new URLSearchParams({ location });
      if (productNumber) params.set('productNumber', productNumber);
      return api.get<JournalEntry[]>(`/api/v1/journals?${params.toString()}`);
    },
    enabled: enabled && location.length > 0,
    staleTime: 30_000,
  });
  return { data: data ?? [], isLoading };
}

/**
 * Real inventory-journal rows correlated to an order (B8a), newest-first.
 * Honest caveat: only pick events carry `correlationId = orderNumber` in the
 * demo seeder (set inline in HistoryGenerator.processDay's pickJournals block)
 * — receipts key on the ASN
 * number instead, so this feed will not show a receiving event for the order.
 */
export function useOrderActivity(
  orderNumber: string,
  enabled = true,
): { data: JournalEntry[]; isLoading: boolean } {
  const { data, isLoading } = useQuery({
    queryKey: ['journals', 'order-activity', orderNumber],
    queryFn: () =>
      api.get<JournalEntry[]>(
        `/api/v1/journals?correlationId=${encodeURIComponent(orderNumber)}`,
      ),
    enabled: enabled && orderNumber.length > 0,
    staleTime: 30_000,
  });
  return { data: data ?? [], isLoading };
}
