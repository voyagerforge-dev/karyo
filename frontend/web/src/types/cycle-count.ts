/**
 * TypeScript interfaces matching cycle-count DTOs.
 * @see services/stocktaking-service/karyo-stocktaking-api/.../dto/StocktakingDtos.kt
 *
 * CountSessionSummaryView: returned by GET /api/v1/count-sessions (paginated list) and
 *                    POST /api/v1/count-sessions (session just started) -- an order-state
 *                    rollup only, never the nested orders→lines graph (defect-burndown row 8:
 *                    the previous unpaginated list GET returned every session's full graph, a
 *                    warehouse-scale OOM risk).
 * CountSessionView:  returned by GET /api/v1/count-sessions/{id} only -- the full nested graph.
 * CountOrderView:    returned by GET /api/v1/count-orders/{id} (review view — planned + counted
 *                    visible); also embedded (full, nested lines and all — NOT a lightweight
 *                    summary) as CountSessionView.orders.
 * CountEntryView:    returned by GET /api/v1/count-orders/{id}?view=entry (blind — no planned/
 *                    counted, no locationId, no state — just enough to render the count form).
 */

// ---------------------------------------------------------------------------
// State codes (forward-only, matching backend VOs)
// ---------------------------------------------------------------------------

/** CountSessionState: OPEN(100), CLOSED(700) */
export type CountSessionState = 100 | 700;

/** CountOrderState: GENERATED(50), COUNTED(500), FINISHED(700), CANCELLED(800) */
export type CountOrderState = 50 | 500 | 700 | 800;

/** CountLineState: PLANNED(50), COUNTED(500), FINISHED(700), CANCELLED(800) */
export type CountLineState = 50 | 500 | 700 | 800;

/** CountCampaignState: OPEN(100), CLOSED(700) */
export type CountCampaignState = 100 | 700;

// ---------------------------------------------------------------------------
// Session
// ---------------------------------------------------------------------------

/** CountType: a selected-scope cycle count, or the whole-warehouse end-of-period inventory. */
export type CountType = 'CYCLE' | 'END_OF_PERIOD';

export interface CountSessionView {
  id: number;
  sessionNumber: string;
  type: string;
  state: CountSessionState;
  /** Full order views (with nested lines), not a lightweight summary. */
  orders: CountOrderView[];
  /** Owning campaign id, or null (St1). */
  campaignId: number | null;
  /**
   * St5 — names of the locations an END_OF_PERIOD start walked past (reserved stock, or an
   * existing non-count lock). Backend-side this is NOT persisted, so it is populated only on
   * the POST /count-sessions response and absent/empty on every later GET; the UI shows it
   * when nonzero and simply doesn't when it isn't. Optional so pre-St5 fixtures stay valid.
   */
  skippedLocations?: string[];
}

/**
 * Lightweight per-session projection -- returned by GET /api/v1/count-sessions (paginated list)
 * and POST /api/v1/count-sessions (the just-started session). Order counts only, never the
 * nested orders→lines graph [CountSessionView] carries (defect-burndown row 8). Fetch
 * GET /api/v1/count-sessions/{id} for the full graph.
 */
export interface CountSessionSummaryView {
  id: number;
  sessionNumber: string;
  type: string;
  state: CountSessionState;
  /** Owning campaign id, or null (St1). */
  campaignId: number | null;
  orderCount: number;
  /** Count of orders in CountOrderState.COUNTED (500) -- awaiting manager review. */
  countedCount: number;
  /** Count of orders in CountOrderState.FINISHED (700). */
  finishedCount: number;
  /** St5 — see [CountSessionView.skippedLocations]; same not-persisted, start-response-only
   *  semantics. Optional so pre-St5 fixtures stay valid. */
  skippedLocations?: string[];
  /** Declared for interface stability -- a later task populates this; empty/absent until then. */
  skippedLocationIds?: number[];
}

// ---------------------------------------------------------------------------
// Count Order (review / full view — planned + counted visible)
// ---------------------------------------------------------------------------

export interface CountOrderView {
  id: number;
  orderNumber: string;
  sessionId: number;
  locationId: number;
  locationName: string;
  state: CountOrderState;
  lines: CountLineView[];
}

export interface CountLineView {
  id: number;
  stockUnitId: number;
  itemDataNumber: string;
  lotNumber: string | null;
  /** Backend BigDecimal — always set for review view */
  plannedAmount: number;
  /** Backend BigDecimal? — null until counted */
  countedAmount: number | null;
  state: CountLineState;
  /** St4 — snapshotted unit-load identity; null/absent for pre-migration lines. Optional so
   *  existing review-view fixtures/mocks predating St4 remain valid. */
  unitLoadId?: number | null;
  unitLoadLabel?: string | null;
}

// ---------------------------------------------------------------------------
// Count Entry (blind view — ?view=entry — no planned/counted amounts)
// ---------------------------------------------------------------------------

export interface CountEntryView {
  id: number;
  orderNumber: string;
  locationName: string;
  lines: CountEntryLine[];
}

export interface CountEntryLine {
  lineId: number;
  itemDataNumber: string;
  lotNumber: string | null;
  serialNumber: string | null;
  /** St4 — grouping key for the entry form (`?? 'Loose stock'`) and the id sent to
   *  POST .../unit-loads/missing. */
  unitLoadId: number | null;
  unitLoadLabel: string | null;
  /** St4 — true once this line was zeroed by the missing-op (or otherwise already counted);
   *  the entry form locks its input without revealing the actual counted amount. */
  counted: boolean;
}

export interface UnitLoadMissingRequest {
  unitLoadId: number;
}

// ---------------------------------------------------------------------------
// Request shapes
// ---------------------------------------------------------------------------

export interface StartCountRequest {
  locationIds?: number[];
  areaId?: number;
  locationNamePattern?: string;
  blindCount?: boolean;
  /** Optional owning campaign (St1) -- must be OPEN and of the same type as this request. */
  campaignId?: number;
  /**
   * St5 — omit (or 'CYCLE') for a scoped cycle count; 'END_OF_PERIOD' starts a full-warehouse
   * inventory, in which case locationIds/areaId/locationNamePattern MUST be omitted (the
   * backend 422s them rather than ignoring them).
   */
  type?: CountType;
}

export interface SubmitCountRequest {
  lines: SubmitCountLine[];
}

export interface SubmitCountLine {
  lineId: number;
  countedAmount: number;
}

// ---------------------------------------------------------------------------
// Campaign (St1)
// ---------------------------------------------------------------------------

export interface CreateCampaignRequest {
  name: string;
  /** Defaults to CYCLE server-side when omitted. */
  type?: string;
}

/** Plain campaign view -- returned by POST/GET /count-campaigns (list form, no rollup). */
export interface CountCampaignView {
  id: number;
  campaignNumber: string;
  name: string;
  type: string;
  state: CountCampaignState;
  started: string | null;
  ended: string | null;
}

/** Order-state bucket for [CountCampaignRollupView.ordersByState]. */
export interface OrdersByStateView {
  generated: number;
  counted: number;
  finished: number;
  cancelled: number;
}

/** Detail view -- returned by GET /count-campaigns/{id}. */
export interface CountCampaignRollupView {
  id: number;
  campaignNumber: string;
  name: string;
  type: string;
  state: CountCampaignState;
  started: string | null;
  ended: string | null;
  sessions: number;
  ordersByState: OrdersByStateView;
  discrepancyLines: number;
}
