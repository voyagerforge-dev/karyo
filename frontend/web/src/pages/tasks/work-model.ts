import type { EntityTone } from '@/components/master-detail/tones';
import type { WorkItemResponse, WorkTypeName } from '@/types/work';
import type { TransportOrderResponse } from '@/types/tasks';

export const WORK_TYPE_META: Record<WorkTypeName, { label: string; tone: EntityTone }> = {
  PICK: { label: 'Pick', tone: 'lime' },
  PUTAWAY: { label: 'Putaway', tone: 'blue' },
  MOVE: { label: 'Move', tone: 'grey' },
  REPLENISH: { label: 'Replenish', tone: 'amber' },
  COUNT: { label: 'Count', tone: 'violet' },
  // 'red' is the only EntityTone not already claimed by another work type —
  // not a danger signal here, just the last color in the palette.
  RECEIVE: { label: 'Receive', tone: 'red' },
  // PT15: TRANSFER is a chain successor of a PUTAWAY/MOVE/REPLENISH hop, not a new kind of
  // work from the operator's chair -- it reuses MOVE's tone rather than claiming a new one.
  TRANSFER: { label: 'Transfer', tone: 'grey' },
  // Cross-docking sprint (fix-round): CROSS_DOCK rides the regular merged work-inbox like every
  // other transport type (TransportWorkProvider.workTypes() includes it) as well as the
  // type-unfiltered Paused lane -- all 6 tones are already claimed above, so it reuses
  // PUTAWAY's (both are dock-to-storage transports).
  CROSS_DOCK: { label: 'Cross-dock', tone: 'blue' },
};

/** Row 22: the "Paused" chip is not a work type -- it selects a separate, directly-fetched
 *  list (paused transport orders never appear in the work-inbox queries at all), so it
 *  is not covered by `matchesWorkFilter`. */
export type WorkFilter = 'all' | WorkTypeName | 'PAUSED';

export function matchesWorkFilter(item: Pick<WorkItemResponse, 'workType'>, filter: WorkFilter): boolean {
  if (filter === 'all') return true;
  return item.workType === filter;
}

export function mergeWork(available: WorkItemResponse[], mine: WorkItemResponse[]): WorkItemResponse[] {
  const seen = new Set<string>();
  const result: WorkItemResponse[] = [];

  // Add mine first
  for (const item of mine) {
    if (!seen.has(item.ref)) {
      result.push(item);
      seen.add(item.ref);
    }
  }

  // Add available, skipping dupes
  for (const item of available) {
    if (!seen.has(item.ref)) {
      result.push(item);
      seen.add(item.ref);
    }
  }

  return result;
}

/**
 * Row 22: synthesizes the ref shape `WorkRow`/`WorkDetailPane`/`TransportPane` expect from
 * a raw `TransportOrderResponse` -- the paused list is fetched directly from
 * `GET /api/v1/transport-orders?paused=true` (task-service), not the work-inbox, so it
 * never arrives in the `WorkItemResponse` shape those components render. Mirrors the
 * `refType`/`refSourceId` convention (`{transportType}:{id}`) so the existing claim/
 * release/detail plumbing works unchanged once a paused row is selected.
 */
export function transportOrderToWorkItem(order: TransportOrderResponse): WorkItemResponse {
  return {
    ref: `${order.transportType}:${order.id}`,
    workType: order.transportType,
    priority: order.prio,
    state: order.operatorId != null ? 'CLAIMED' : 'OPEN',
    claimedBy: order.operatorId,
    zone: null,
    primaryLocation: order.sourceLocationName,
    destination: order.destinationLocationName ?? order.suggestedLocationName,
    summary: `${order.orderNumber} · ${order.unitLoadLabel}`,
    createdAt: order.created,
  };
}
