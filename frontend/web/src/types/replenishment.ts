/**
 * TypeScript interfaces matching replenishment DTOs.
 * @see services/replenishment-service/karyo-replenishment-api/.../ReplenishmentDtos.kt
 *
 * ReplenishmentNeed: a fixed-location assignment that is below its minimum quantity
 * or approaching it. Produced by GET /api/v1/replenishment/needs.
 *
 * ReplenishmentScanResult: returned by POST /api/v1/replenishment/scan — the system
 * scans all needs and attempts to generate one REPLENISH transport order per need
 * (fix-face, Mode 1) plus one per deficient ItemDataArea (area-level, Mode 2, R12b).
 */

export interface ReplenishmentNeed {
  fixAssignmentId: number;
  locationId: number;
  locationName: string;
  itemDataId: number;
  itemDataNumber: string | null;
  currentAmount: number;
  minAmount: number | null;
  desiredAmount: number | null;
  belowMin: boolean;
  hasOpenTask: boolean;
}

// R12b: fixAssignmentId/itemDataAreaId are mutually exclusive — a Mode-1 (fix-face) row sets
// fixAssignmentId and leaves itemDataAreaId null; a Mode-2 (area-level) row is the inverse.
export interface GeneratedTask {
  taskId: number;
  orderNumber: string;
  fixAssignmentId: number | null;
  locationName: string;
  itemDataNumber: string | null;
  unitLoadId: number;
  itemDataAreaId: number | null;
}

export interface ReplenishmentShortfall {
  fixAssignmentId: number | null;
  locationName: string;
  itemDataNumber: string | null;
  currentAmount: number;
  minAmount: number | null;
  reason: string; // "NO_SOURCE" | "NO_DESTINATION" (area-only)
  itemDataAreaId: number | null;
}

export interface ReplenishmentScanResult {
  generated: GeneratedTask[];
  shortfalls: ReplenishmentShortfall[];
}
