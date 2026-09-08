import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query';
import { api } from '@/lib/api-client';
import { sortingStateToString } from '@/lib/table-utils';
import type { PaginatedResponse } from '@/types/api';
import type { FixAssignmentResponse } from '@/types/location';
import type {
  AdjustStockRequest,
  ChangeClientRequest,
  LockUnitLoadRequest,
  SetCarrierRequest,
  SetLockRequest,
  StockUnitResponse,
  TransferToClearingRequest,
  TransferUnitLoadRequest,
  UnitLoadResponse,
} from '@/types/inventory';

export { sortingStateToString };

interface UseListOptions {
  page: number;
  size: number;
  sort?: string;
}

/**
 * Fetch paginated stock units with server-side sort/pagination.
 */
export function useStockUnits(options: UseListOptions) {
  const { page, size, sort } = options;
  return useQuery({
    queryKey: ['stock-units', { page, size, sort }],
    queryFn: () =>
      api.get<PaginatedResponse<StockUnitResponse>>(
        `/api/v1/stock-units?page=${page}&size=${size}${sort ? `&sort=${sort}` : ''}`,
      ),
    staleTime: 30_000,
  });
}

/**
 * Fetch all fix-assignments for this tenant (non-paginated) — the source of
 * the reorder-point signal (`minAmount`) shown on Inventory. Shares the
 * React Query cache key with any other consumer of the same endpoint.
 */
export function useFixAssignments() {
  return useQuery({
    queryKey: ['fix-assignments', 'all'],
    queryFn: () => api.get<FixAssignmentResponse[]>('/api/v1/fix-assignments'),
    staleTime: 30_000,
  });
}

/** Builds the `${itemDataId}@${locationId}` -> minAmount reorder-point map used by `withReorderPoints`. */
export function toReorderPointMap(assignments: FixAssignmentResponse[]): Map<string, number> {
  const map = new Map<string, number>();
  for (const a of assignments) {
    if (a.minAmount === null) continue;
    map.set(`${a.itemDataId}@${a.locationId}`, a.minAmount);
  }
  return map;
}

/** Invalidate the queries any inventory mutation can affect: the stock-unit
 *  list (amounts/lock/location), the locations list (occupancy), every
 *  journal query (adjust/lock/transfer all write a new InventoryJournal row,
 *  which the Movement ledger on the same screen reads via useJournals), and
 *  the per-location unit-load list (pallet-level lock state/location). */
function invalidateInventoryQueries(queryClient: ReturnType<typeof useQueryClient>) {
  queryClient.invalidateQueries({ queryKey: ['stock-units'] });
  queryClient.invalidateQueries({ queryKey: ['locations'] });
  queryClient.invalidateQueries({ queryKey: ['journals'] });
  queryClient.invalidateQueries({ queryKey: ['unit-loads'] });
}

/**
 * Fetch every unit load at a location — the source of pallet-level
 * `lockType`/`lockTypeName` for the Stored-units rows (StockUnitResponse
 * carries no unit-load lock field, so this is a one-per-selected-group fetch
 * rather than one per LPN row).
 */
export function useUnitLoadsByLocation(locationId: number | undefined) {
  return useQuery({
    queryKey: ['unit-loads', 'by-location', locationId],
    queryFn: () => api.get<UnitLoadResponse[]>(`/api/v1/unit-loads?locationId=${locationId}`),
    enabled: locationId != null,
    staleTime: 15_000,
  });
}

/** Sets a stock unit's on-hand amount to an absolute value (`POST /adjust`). */
export function useAdjustStock() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, newAmount }: { id: number; newAmount: number }) =>
      api.post<StockUnitResponse>(`/api/v1/stock-units/${id}/adjust`, {
        newAmount,
        activityCode: 'MANUAL_ADJUST',
      } satisfies AdjustStockRequest),
    onSuccess: () => invalidateInventoryQueries(queryClient),
  });
}

/** Locks/unlocks a stock unit, recording the reason as the journal activityCode. */
export function useSetStockLock() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, lockType, reason }: { id: number; lockType: number; reason?: string }) =>
      api.post<StockUnitResponse>(`/api/v1/stock-units/${id}/lock`, {
        lockType,
        reason,
      } satisfies SetLockRequest),
    onSuccess: () => invalidateInventoryQueries(queryClient),
  });
}

/** Relocates a whole unit load to a destination location. */
export function useTransferUnitLoad() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      destinationLocationId,
      destinationLocationName,
    }: {
      id: number;
      destinationLocationId: number;
      destinationLocationName: string;
    }) =>
      api.post<UnitLoadResponse>(`/api/v1/unit-loads/${id}/transfer`, {
        destinationLocationId,
        destinationLocationName,
        activityCode: 'MANUAL_MOVE',
      } satisfies TransferUnitLoadRequest),
    onSuccess: () => invalidateInventoryQueries(queryClient),
  });
}

/**
 * Reassigns a unit load's goods-owner (client). The backend additionally
 * requires an OPS principal server-side and total-refuses (409) on
 * reservations/open picks/state >= PICKED — no special handling here, the
 * global RFC7807 toast surfaces the human-readable detail either way.
 */
export function useChangeUnitLoadClient() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({
      id,
      targetClientId,
      activityCode,
    }: {
      id: number;
      targetClientId: number;
      activityCode?: string;
    }) =>
      api.post<UnitLoadResponse>(`/api/v1/unit-loads/${id}/change-client`, {
        targetClientId,
        activityCode,
      } satisfies ChangeClientRequest),
    onSuccess: () => invalidateInventoryQueries(queryClient),
  });
}

/** Flags/unflags a unit load as a carrier (a reusable tote/cart, not stock itself). */
export function useSetUnitLoadCarrier() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, isCarrier }: { id: number; isCarrier: boolean }) =>
      api.post<UnitLoadResponse>(`/api/v1/unit-loads/${id}/carrier`, {
        isCarrier,
      } satisfies SetCarrierRequest),
    onSuccess: () => invalidateInventoryQueries(queryClient),
  });
}

/**
 * Applies a pallet-level lock to a whole unit load (A2-3) — excludes every
 * constituent stock unit from selection until unlocked. `lockType` defaults
 * to `LockType.GENERAL(1)` server-side when omitted.
 */
export function useLockUnitLoad() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, lockType, note }: { id: number; lockType?: number; note?: string }) =>
      api.post<UnitLoadResponse>(`/api/v1/unit-loads/${id}/lock`, {
        lockType,
        note,
      } satisfies LockUnitLoadRequest),
    onSuccess: () => invalidateInventoryQueries(queryClient),
  });
}

/** Clears a pallet-level lock on a unit load (A2-3). */
export function useUnlockUnitLoad() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: (id: number) => api.post<UnitLoadResponse>(`/api/v1/unit-loads/${id}/unlock`, {}),
    onSuccess: () => invalidateInventoryQueries(queryClient),
  });
}

/**
 * Moves a unit load to the facility's flagged clearing location and
 * recursively locks it there for disposition (A2-1). 409 `not-configured`
 * when no location is flagged `isClearing` — surfaced by the global toast,
 * no special handling here.
 */
export function useTransferToClearing() {
  const queryClient = useQueryClient();
  return useMutation({
    mutationFn: ({ id, note }: { id: number; note?: string }) =>
      api.post<UnitLoadResponse>(`/api/v1/unit-loads/${id}/transfer-to-clearing`, {
        note,
      } satisfies TransferToClearingRequest),
    onSuccess: () => invalidateInventoryQueries(queryClient),
  });
}

/**
 * Fan out a Move to one transfer call per distinct unit-load id — pulled out
 * of the Move dialog's submit handler so the fan-out (dedup + one call per
 * id) is unit-testable without driving the destination `Select` in jsdom.
 * `transfer` is typically `useTransferUnitLoad().mutateAsync`.
 */
export async function moveUnitLoads(
  unitLoadIds: number[],
  destination: { id: number; name: string },
  transfer: (args: {
    id: number;
    destinationLocationId: number;
    destinationLocationName: string;
  }) => Promise<unknown>,
): Promise<void> {
  const distinctIds = Array.from(new Set(unitLoadIds));
  await Promise.all(
    distinctIds.map((id) =>
      transfer({
        id,
        destinationLocationId: destination.id,
        destinationLocationName: destination.name,
      }),
    ),
  );
}
