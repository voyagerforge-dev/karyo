import { describe, it, expect } from 'vitest';
import type { PickOrderResponse } from '@/types/pick-orders';
import type { Shipment } from '@/types/shipments';
import { deriveReadyToPack } from '../use-packing';

const po = (id: number, state: number, deliveryOrderId: number | null): PickOrderResponse => ({
  id, pickOrderNumber: `PO-${id}`, deliveryOrderId,
  deliveryOrderNumber: deliveryOrderId != null ? `DO-${deliveryOrderId}` : null,
  state, targetUnitLoadId: 9, picks: [], weight: null, volume: null, destinationLocationId: null,
  bulk: false,
  autoOpenPending: false,
});
const shp = (id: number, deliveryOrderId: number, state = 640): Shipment => ({
  id, shipmentNumber: `SHP-${id}`, deliveryOrderId, deliveryOrderNumber: `DO-${deliveryOrderId}`,
  state, shippingUnits: [],
});

describe('deriveReadyToPack', () => {
  it('includes PICKED orders with no shipment; excludes picked-but-shipped and non-PICKED', () => {
    const result = deriveReadyToPack(
      [po(1, 600, 101), po(2, 600, 102), po(3, 500, 103)],
      [shp(50, 102)],
    );
    expect(result.map((p) => p.id)).toEqual([1]); // 2 already has a shipment, 3 not PICKED
  });

  it('handles empty inputs', () => {
    expect(deriveReadyToPack([], [])).toEqual([]);
  });

  // Row 20: an EXTINGUISH order (no backing DeliveryOrder) has nothing to
  // pack against -- excluded even when PICKED, not leaked in with a null id.
  it('excludes a PICKED order with no delivery order (EXT-)', () => {
    const result = deriveReadyToPack([po(1, 600, 101), po(4, 600, null)], []);
    expect(result.map((p) => p.id)).toEqual([1]);
  });

  // Bulk Allocation Sprint C: a group shipment has no deliveryOrderId of its own --
  // its packed member orders come from `orders` instead.
  it('excludes member orders already packed under a group shipment', () => {
    const groupShipment: Shipment = {
      id: 60,
      shipmentNumber: 'SHP-60',
      deliveryOrderId: null,
      deliveryOrderNumber: null,
      orders: [{ id: 101, number: 'DO-101' }],
      state: 640,
      shippingUnits: [],
    };
    const result = deriveReadyToPack([po(1, 600, 101), po(2, 600, 102)], [groupShipment]);
    expect(result.map((p) => p.id)).toEqual([2]);
  });
});
