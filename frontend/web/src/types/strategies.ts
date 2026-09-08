import type { OrderStrategyResponse } from '@/types/orders';

export type { OrderStrategyResponse };

/**
 * `releaseMode` lives inside `OrderStrategyResponse.extensionProperties` (untyped on the
 * backend DTO) -- these are the only three values the streaming design allows, default
 * `MANUAL`. `STREAM_KEYS` are the five extension-property keys the order-strategy form's
 * typed fields own: the free-form JSON textarea seed strips them, and submit re-merges the
 * typed values back in (typed wins over anything left in the textarea).
 */
export const RELEASE_MODES = ['MANUAL', 'WAVE', 'STREAM'] as const;
export type ReleaseMode = (typeof RELEASE_MODES)[number];
export const STREAM_KEYS = [
  'releaseMode',
  'streamBatchSize',
  'streamMaxWaitSeconds',
  'streamAbandonSeconds',
  'streamTimingStrategy',
] as const;

export interface CreateOrderStrategyRequest {
  name: string;
  useLockedStock: boolean;
  preferComplete: boolean;
  preferMatching: boolean;
  completeHandling: number;
  enforceLot: boolean;
  shortPickMode: string;
  shortfallStrategy: string;
  pickDifferenceStrategy: string;
  packoutStrategy: string;
  extensionProperties: Record<string, unknown>;
  sendToPacking: boolean;
  sendToShipping: boolean;
  createShippingOrder: boolean;
  createTypeOrders: boolean;
  defaultDestinationLocationId?: number;
}

/**
 * `defaultDestinationLocationId` is tri-state on the backend (`Patchable<Long>`, :1457
 * 2026-08-17): key omitted = leave unchanged, explicit `null` = clear, a value = set.
 * `undefined` here maps to "omit the key"; `order-strategy-form.tsx`'s `patchIdField` call
 * is what actually sends `null` for a cleared field.
 */
export interface UpdateOrderStrategyRequest {
  useLockedStock?: boolean;
  preferComplete?: boolean;
  preferMatching?: boolean;
  completeHandling?: number;
  enforceLot?: boolean;
  shortPickMode?: string;
  shortfallStrategy?: string;
  pickDifferenceStrategy?: string;
  packoutStrategy?: string;
  extensionProperties?: Record<string, unknown>;
  sendToPacking?: boolean;
  sendToShipping?: boolean;
  createShippingOrder?: boolean;
  createTypeOrders?: boolean;
  defaultDestinationLocationId?: number | null;
}

export interface StorageStrategyResponse {
  id: number;
  name: string;
  zoneId: number | null;
  mixItem: boolean;
  mixClient: boolean;
  nearPickingLocation: boolean;
  sorts: string | null;
  onlyClientLocation: boolean;
  manualSearch: boolean;
  useAreaStrategyDate: boolean;
  useItemDataArea: boolean;
  created: string;
  modified: string;
}

export interface CreateStorageStrategyRequest {
  name: string;
  zoneId?: number;
  mixItem: boolean;
  mixClient: boolean;
  nearPickingLocation: boolean;
  sorts?: string;
  onlyClientLocation: boolean;
  manualSearch: boolean;
  useAreaStrategyDate: boolean;
  useItemDataArea: boolean;
}
