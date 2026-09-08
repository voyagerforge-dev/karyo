/**
 * Wave-based bulk fulfillment DTOs (v2.x Advanced Fulfillment pack).
 * Mirrors `com.karyo.wave.dto.WaveDtos` (services/wave-service/karyo-wave-api).
 *
 * The backend serializes every state field as its enum NAME (via
 * `WaveState.fromCode(state).name` / `ConsolidationState.fromCode(state).name`), never the
 * raw numeric code -- the code values below are kept only as a lookup table for
 * ordering/gating decisions on the frontend (e.g. "is this state before COMPLETED").
 */

export type WaveStateName =
  | 'PLANNED'
  | 'RELEASED'
  | 'PICKING'
  | 'CONSOLIDATING'
  | 'COMPLETED'
  | 'CANCELLED';

/** `com.karyo.wave.vo.WaveState` codes. */
export const WAVE_STATE_CODE: Record<WaveStateName, number> = {
  PLANNED: 100,
  RELEASED: 300,
  PICKING: 400,
  CONSOLIDATING: 500,
  COMPLETED: 700,
  CANCELLED: 900,
};

/** Every filterable wave state, in lifecycle order. */
export const WAVE_STATES: readonly WaveStateName[] = [
  'PLANNED',
  'RELEASED',
  'PICKING',
  'CONSOLIDATING',
  'COMPLETED',
  'CANCELLED',
];

export type ConsolidationStateName = 'PENDING' | 'IN_PROGRESS' | 'READY' | 'SHIPPED';

/** `com.karyo.wave.vo.ConsolidationState` codes. */
export const CONSOLIDATION_STATE_CODE: Record<ConsolidationStateName, number> = {
  PENDING: 100,
  IN_PROGRESS: 200,
  READY: 400,
  SHIPPED: 700,
};

/** `com.karyo.wave.vo.WavePickMode`. */
export const WAVE_PICK_MODES = ['HYBRID', 'COMPLETE_ONLY', 'PICK_ONLY', 'BULK'] as const;
export type WavePickModeName = (typeof WAVE_PICK_MODES)[number];

/** `com.karyo.wave.vo.AllocationShortageAction`. */
export const WAVE_SHORTAGE_ACTIONS = ['SKIP', 'SHORT_ALLOCATE', 'HOLD_ORDER', 'HOLD_WAVE'] as const;
export type WaveShortageActionName = (typeof WAVE_SHORTAGE_ACTIONS)[number];

export interface WaveResponse {
  id: number;
  waveNumber: string;
  state: WaveStateName;
  orderStrategyId: number;
  wavePickMode: string;
  shortageAction: string;
  plannedReleaseAt: string | null;
  releasedAt: string | null;
  completedAt: string | null;
  totalOrders: number;
  totalLines: number;
  created: string;
  // Selection-rules sprint, Task 4: selection provenance -- "explicit" for an explicit-ids
  // create, the resolved key otherwise; selectionRuleName is resolved live, "#<id>" if the
  // bound rule was since deleted.
  selectionStrategy: string | null;
  selectionRuleName: string | null;
}

export interface WaveOrderSummary {
  orderId: number;
  orderNumber: string;
  /** Backend `OrderState` name (e.g. "PROCESSABLE", "PICKED"). */
  state: string;
  prio: number;
  customerName: string | null;
  /** FOLD-6 (review): ISO `yyyy-MM-dd`, null when the order carries no delivery date. */
  deliveryDate: string | null;
}

/** `action` is the wave's own `shortageAction`, display-only -- no per-line action is stored. */
export interface WaveShortage {
  orderId: number;
  lineId: number;
  itemDataNumber: string;
  requested: number;
  reserved: number;
  action: string;
}

export interface ConsolidationGroupResponse {
  id: number;
  destinationKey: string;
  state: ConsolidationStateName;
  consolidationLocationId: number | null;
  totalPickOrders: number;
  completedPickOrders: number;
  // Bulk-allocation sort-station sprint (Task 7): the put-wall slot the group sorts into,
  // and how many of the picked units for this group have been sorted there so far.
  sortSlot: string;
  pickedAmount: number;
  sortedAmount: number;
  // Bulk-allocation Sprint C (Task 6): pack-out status -- how many of the sorted units for
  // this group have been packed so far, and the group shipment once packing opens on it.
  packedAmount: number;
  shipmentId: number | null;
}

export interface WaveDetailResponse {
  wave: WaveResponse;
  orders: WaveOrderSummary[];
  shortages: WaveShortage[];
  groups: ConsolidationGroupResponse[];
  // Bulk-allocation Sprint C (Task 6): wave members whose batch picks are all terminal yet
  // delivered nothing -- every wave pick reached PICKED or beyond with zero picked quantity, so
  // there is nothing to sort and nothing to pack. Surfaced so nothing silently vanishes.
  unfilledOrders: WaveOrderSummary[];
}

export interface WaveProgressResponse {
  waveId: number;
  state: WaveStateName;
  totalOrders: number;
  totalLines: number;
  pickedLines: number;
  openPickOrders: number;
  groupsReady: number;
  groupsTotal: number;
}

export interface CreateWaveRequest {
  orderStrategyId: number;
  deliveryOrderIds?: number[];
  plannedReleaseAt?: string;
  wavePickMode?: string;
  shortageAction?: string;
  // Selection-rules sprint, Task 4: per-request override, wins over the strategy's own config.
  selectionStrategy?: string;
  selectionRuleId?: number;
}

// ── Selection rules (selection-rules sprint, Tasks 1-4) ─────────────────────
// Mirrors `com.karyo.wave.rule.SelectionRule`/`SelectionCondition` and the
// `WaveSelectionRuleResource` DTOs exactly.

/** `com.karyo.wave.rule.FieldType`. */
export type SelectionFieldType = 'STRING' | 'NUMBER' | 'DATE' | 'DATETIME';

/** One row of `GET /api/v1/wave-selection-rules/fields` -- the field registry, in registry order. */
export interface SelectionFieldResponse {
  field: string;
  type: SelectionFieldType;
  label: string;
  ops: string[];
}

/** A condition's value: scalar, an array (in/between), or absent (isNull/notNull). */
export type SelectionConditionValue = string | number | Array<string | number> | null | undefined;

export interface SelectionCondition {
  field: string;
  op: string;
  value?: SelectionConditionValue;
}

/** `com.karyo.wave.rule.SelectionRule` -- one nesting level of `groups`, depth cap 2 total. */
export interface SelectionRule {
  combinator: 'AND' | 'OR';
  conditions: SelectionCondition[];
  groups: SelectionRule[];
}

export interface SelectionRuleResponse {
  id: number;
  name: string;
  description: string | null;
  definition: SelectionRule;
  boundByStrategies: number;
  created: string;
}

export interface RulePreviewResponse {
  matchedCount: number;
  poolSize: number;
  sample: WaveOrderSummary[];
}
