export interface ShippingUnit {
  id: number;
  shippingUnitNumber: string;
  type: string; // "CARTON" | "PALLET"
  weight: number;
  state: number;
  unitLoadId: number | null;
  positionIndex: number;
  carrierLabel?: string | null;
  trackingNumber?: string | null;
  shipToName?: string | null;
  shipToStreet?: string | null;
  shipToStreetNumber?: string | null;
  shipToZip?: string | null;
  shipToCity?: string | null;
  shipToCountry?: string | null;
  origin: string; // "PACKOUT" | "AD_HOC"
}

export interface Shipment {
  id: number;
  shipmentNumber: string;
  /** Null for a wave group shipment (Bulk Allocation Sprint C) -- packed from a
   *  consolidation group's carts, not a single delivery order. */
  deliveryOrderId: number | null;
  deliveryOrderNumber: string | null;
  /** Set only on a group shipment; the consolidation group it was packed from. */
  consolidationGroupId?: number | null;
  waveId?: number | null;
  /** Member delivery orders on a group shipment. Absent/empty on a plain single-order shipment. */
  orders?: Array<{ id: number; number: string }>;
  state: number;
  shippingUnits: ShippingUnit[];
  carrierName?: string | null;
  carrierService?: string | null;
  trackingNumber?: string | null;
  /** S3: claiming operator -- pure metadata, never coupled to `state`. */
  operatorId?: string | null;
  /** S3: orthogonal pause stamp; non-null = paused (`state` does not move). */
  pausedAt?: string | null;
}

export interface AddAdHocUnitRequest {
  unitLoadId: number;
}

export interface OpenPackingRequest {
  deliveryOrderId: number;
}

export interface PackRequest {
  weight: number;
  type?: string;
}

export interface ManifestRequest {
  carrierName: string;
  carrierService: string;
  trackingNumber?: string;
}
