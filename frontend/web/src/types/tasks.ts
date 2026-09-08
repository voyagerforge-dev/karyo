/**
 * TypeScript interfaces matching task-service transport-order DTOs.
 * @see services/task-service/karyo-tasks-api/src/main/kotlin/com/karyo/tasks/dto/TransportOrderDtos.kt
 *
 * Transport orders are PUTAWAY (auto-created by receiving), MOVE (manual), REPLENISH
 * (auto-created by the replenishment engine), or -- PT15 -- TRANSFER (auto-created chain
 * successor, see ChainContinuationService). They carry the shared OrderState lifecycle:
 * CREATED(50) → RELEASED(100) → RESERVED(400) → STARTED(500) → FINISHED(700), or
 * CANCELED(800). PT17/PT18 add an orthogonal pause stamp plus ERP refs, denormed
 * item/lot/amount, and partial/merge-confirm fields (see field-level KDoc on the DTO).
 */

export interface TransportOrderResponse {
  id: number;
  orderNumber: string;
  transportType: string; // "PUTAWAY" | "MOVE" | "REPLENISH" | "TRANSFER"
  unitLoadId: number;
  unitLoadLabel: string;
  sourceLocationId: number;
  sourceLocationName: string;
  destinationLocationId: number | null;
  destinationLocationName: string | null;
  suggestedLocationId: number | null;
  suggestedLocationName: string | null;
  state: number;
  stateName: string; // e.g., "RELEASED", "STARTED", "FINISHED"
  prio: number;
  operatorId: string | null;
  executorType: string;
  note: string | null;
  goodsReceiptLineId: number | null;
  clientId: number;
  created: string;
  modified: string;
  /** PT18: orthogonal pause stamp -- non-null means paused; `state` never moves. */
  pausedAt: string | null;
  started: string | null;
  finished: string | null;
  /** PT15: non-null when this order chained onto a TRANSFER successor at completion. */
  successorId: number | null;
  /** PT17: caller's ERP reference pair -- only ever set on a manual MOVE. */
  externalNumber: string | null;
  externalId: string | null;
  /** PT17 denorm-at-creation -- non-null only when the order's unit load carried
   *  exactly one live stock unit at creation (the precondition for a partial confirm). */
  itemDataId: number | null;
  itemDataNumber: string | null;
  lotNumber: string | null;
  amount: number | null;
  /** PT17: written on every confirm path (whole-UL, merge, or partial). */
  confirmedAmount: number | null;
  sourceStockUnitId: number | null;
}

export interface CreateTransportOrderRequest {
  unitLoadId: number;
  destinationLocationId: number;
  destinationLocationName?: string;
  prio?: number;
  /** PT17: caller's ERP reference pair, threaded only through the manual-move path. */
  externalNumber?: string;
  externalId?: string;
}

export interface AssignTransportOrderRequest {
  operatorId: string;
}

/**
 * destinationLocationId/Name and destinationUnitLoadId are mutually exclusive (400 if
 * both are supplied -- see TaskService.complete). PT17 `amount` requests a partial
 * confirm; omitted (the default) is the existing whole-UL/merge behavior.
 */
export interface CompleteTransportOrderRequest {
  destinationLocationId?: number;
  destinationLocationName?: string;
  /** PT16: confirm-merge into an EXISTING unit load instead of a location. */
  destinationUnitLoadId?: number;
  /** PT17: request a partial confirm of this amount rather than the whole source stock. */
  amount?: number;
}

/** Shared OrderState codes used by transport orders. */
export const TRANSPORT_ORDER_STATE = {
  CREATED: 50,
  RELEASED: 100,
  RESERVED: 400,
  STARTED: 500,
  FINISHED: 700,
  CANCELED: 800,
} as const;
