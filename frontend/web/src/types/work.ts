export interface WorkItemResponse {
  ref: string;
  workType: string;
  priority: number;
  state: 'OPEN' | 'CLAIMED';
  claimedBy: string | null;
  zone: string | null;
  primaryLocation: string | null;
  destination: string | null;
  summary: string;
  createdAt: string;
}

// Cross-docking sprint (fix-round): CROSS_DOCK transport orders flow through the generic
// work-inbox like every other transport type (TransportWorkProvider.workTypes() includes it),
// as well as the type-unfiltered Paused lane (transportOrderToWorkItem / WorkRow's
// WORK_TYPE_META lookup). The type is listed here so that lookup stays exhaustive.
export const WORK_TYPES = ['PICK', 'PUTAWAY', 'MOVE', 'REPLENISH', 'COUNT', 'RECEIVE', 'TRANSFER', 'CROSS_DOCK'] as const;
export type WorkTypeName = typeof WORK_TYPES[number];

/** Parse ref "PICK:42" → 42 */
export function refSourceId(ref: string): number {
  const parts = ref.split(':');
  return parseInt(parts[1], 10);
}

/** Parse ref "PICK:42" → "PICK" */
export function refType(ref: string): WorkTypeName {
  const parts = ref.split(':');
  return parts[0] as WorkTypeName;
}
