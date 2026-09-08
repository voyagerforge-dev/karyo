import type { EntityTone } from '@/components/master-detail/tones';
import type { ConsolidationStateName, WaveStateName } from '@/types/waves';
import { WAVE_STATE_CODE } from '@/types/waves';

/** Master-detail rail / pill tone per wave lifecycle state. */
export const WAVE_STATE_TONE: Record<WaveStateName, EntityTone> = {
  PLANNED: 'grey',
  RELEASED: 'amber',
  PICKING: 'lime',
  CONSOLIDATING: 'blue',
  COMPLETED: 'lime',
  CANCELLED: 'red',
};

export const WAVE_STATE_LABEL: Record<WaveStateName, string> = {
  PLANNED: 'Planned',
  RELEASED: 'Released',
  PICKING: 'Picking',
  CONSOLIDATING: 'Consolidating',
  COMPLETED: 'Completed',
  CANCELLED: 'Cancelled',
};

export const CONSOLIDATION_STATE_TONE: Record<ConsolidationStateName, EntityTone> = {
  PENDING: 'grey',
  IN_PROGRESS: 'amber',
  READY: 'blue',
  SHIPPED: 'lime',
};

export const CONSOLIDATION_STATE_LABEL: Record<ConsolidationStateName, string> = {
  PENDING: 'Pending',
  IN_PROGRESS: 'In progress',
  READY: 'Ready',
  SHIPPED: 'Shipped',
};

/** A wave can only be released from PLANNED. */
export function canRelease(state: WaveStateName): boolean {
  return state === 'PLANNED';
}

/** Mirrors `WaveState.canAdvanceTo(CANCELLED)`: allowed while code < COMPLETED(700). */
export function canCancel(state: WaveStateName): boolean {
  return WAVE_STATE_CODE[state] < WAVE_STATE_CODE.COMPLETED;
}

/** A consolidation group can be marked ready unless it already is (or has shipped). */
export function canMarkGroupReady(state: ConsolidationStateName): boolean {
  return state === 'PENDING' || state === 'IN_PROGRESS';
}
