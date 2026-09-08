import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { Shipment } from '@/types/shipments';
import type { PickOrderResponse } from '@/types/pick-orders';
import type { DeliveryOrderResponse } from '@/types/orders';
import { PackDetail, type PackSelection } from '../pack-detail';

const openMutate = vi.fn();
const packMutate = vi.fn();
// Mutable per-test data. Referenced only inside the factory's returned
// functions, which run at call time (after hoisting), so `let` is safe here.
let packData: Shipment | undefined;
let openedShipmentData: Shipment | undefined;
let freshShipmentData: Shipment | undefined;
let deliveryOrderData: Partial<DeliveryOrderResponse> | undefined;

vi.mock('../use-packing', () => ({
  useOpenPacking: () => ({ mutate: openMutate, isPending: false, data: openedShipmentData }),
  usePackShipment: () => ({ mutate: packMutate, isPending: false, data: packData }),
  useShipment: vi.fn(() => ({ data: freshShipmentData, isLoading: false })),
}));
vi.mock('@/features/picking/use-pick-orders', () => ({
  usePickOrders: () => ({ data: pickOrders, isLoading: false }),
}));
vi.mock('@/pages/orders/use-orders', () => ({
  useDeliveryOrder: () => ({ data: deliveryOrderData, isLoading: false }),
}));

const pickOrders: PickOrderResponse[] = [{
  id: 1, pickOrderNumber: 'PO-1', deliveryOrderId: 101, deliveryOrderNumber: 'DO-101',
  state: 600, targetUnitLoadId: 9, weight: 12.5, volume: 0.3, destinationLocationId: null,
  bulk: false,
  autoOpenPending: false,
  picks: [{ id: 11, deliveryOrderLineId: 1, itemDataId: 7, itemDataNumber: 'SKU-7', sourceStockUnitId: 3,
    plannedAmount: 60, pickedAmount: 60, state: 600, lotNumber: 'L1', pickingType: 'PICK',
    followUpForPickId: null, substitutedItemDataId: null,
    pickedLotNumber: 'L1', pickedBestBefore: null }],
}, {
  // Row 20: an EXTINGUISH order has no backing DeliveryOrder -- deliveryOrderId: null, same as
  // a group shipment's selection. Present in every test's pickOrders so a null-selection lookup
  // that fails to guard on a real id would wrongly latch onto this one.
  id: 4, pickOrderNumber: 'PO-4', deliveryOrderId: null, deliveryOrderNumber: null,
  state: 600, targetUnitLoadId: 9, weight: null, volume: null, destinationLocationId: null,
  bulk: false,
  autoOpenPending: false,
  picks: [{ id: 14, deliveryOrderLineId: null, itemDataId: 8, itemDataNumber: 'SKU-EXT', sourceStockUnitId: 4,
    plannedAmount: 5, pickedAmount: 5, state: 600, lotNumber: null, pickingType: 'EXTINGUISH',
    followUpForPickId: null, substitutedItemDataId: null,
    pickedLotNumber: null, pickedBestBefore: null }],
}];
const openedShipment: Shipment = {
  id: 50, shipmentNumber: 'SHP-50', deliveryOrderId: 101, deliveryOrderNumber: 'DO-101',
  state: 640, shippingUnits: [],
};
const packedShipment: Shipment = {
  id: 50, shipmentNumber: 'SHP-50', deliveryOrderId: 101, deliveryOrderNumber: 'DO-101',
  state: 650, shippingUnits: [
    { id: 500, shippingUnitNumber: 'SHP-50-SU1', type: 'CARTON', weight: 2.5, state: 650, unitLoadId: 9, positionIndex: 1, origin: 'PACKOUT' },
  ],
};

const readySelection: PackSelection = { mode: 'ready', pickOrderId: 1, deliveryOrderId: 101 };
const inProgressSelection: PackSelection = { mode: 'inProgress', shipmentId: 50, deliveryOrderId: 101 };

beforeEach(() => {
  openMutate.mockClear();
  packMutate.mockClear();
  packData = undefined;
  openedShipmentData = openedShipment;
  freshShipmentData = undefined;
  deliveryOrderData = undefined;
});

describe('PackDetail', () => {
  it('ready mode shows the pick contents to verify and the pack form (write)', () => {
    render(<PackDetail selection={readySelection} canWrite />);
    expect(screen.getByText('SKU-7')).toBeInTheDocument();
    expect(screen.getByTestId('pack-form')).toBeInTheDocument();
  });

  // "in progress" here is the SELECTION mode (a previously-opened shipment, re-selected) --
  // the shipment itself is already terminal (PACKED/650), one call and done. Distinct from the
  // Task 3 test below, where the SHIPMENT is still mid-pack (PACKING/640, multi-call).
  it('inProgress selection on a terminal (PACKED) shipment shows the read-only units summary, no form', () => {
    freshShipmentData = packedShipment;
    render(<PackDetail selection={inProgressSelection} canWrite />);
    expect(screen.getByTestId('packed-summary')).toBeInTheDocument();
    expect(screen.getByText('SHP-50-SU1')).toBeInTheDocument();
    expect(screen.queryByTestId('pack-form')).not.toBeInTheDocument();
  });

  it('hides the pack form without write permission', () => {
    render(<PackDetail selection={readySelection} canWrite={false} />);
    expect(screen.queryByTestId('pack-form')).not.toBeInTheDocument();
  });

  it('flips to the terminal units summary after a one-call pack completes (reads the pack result)', () => {
    packData = packedShipment; // pack mutation resolved with PACKED shipment (complete=true)
    render(<PackDetail selection={readySelection} canWrite />);
    expect(screen.getByTestId('packed-summary')).toBeInTheDocument();
    expect(screen.getByText('SHP-50-SU1')).toBeInTheDocument();
    expect(screen.queryByTestId('pack-form')).not.toBeInTheDocument();
  });

  // Task 3 (multi-call packing): a `complete=false` pack() call persists units but leaves the
  // shipment at PACKING(640) -- the read-only progress summary must render ABOVE the form, both
  // visible, so a second pack() call shows what's already boxed.
  it('PACKING shipment with units already persisted shows the progress summary above the form (multi-call in progress)', () => {
    const midPackShipment: Shipment = {
      id: 50, shipmentNumber: 'SHP-50', deliveryOrderId: 101, deliveryOrderNumber: 'DO-101',
      state: 640, shippingUnits: [
        { id: 500, shippingUnitNumber: 'SHP-50-SU1', type: 'CARTON', weight: 1.0, state: 650, unitLoadId: null, positionIndex: 1, origin: 'PACKOUT' },
      ],
    };
    freshShipmentData = midPackShipment;
    render(<PackDetail selection={inProgressSelection} canWrite />);

    expect(screen.getByTestId('packed-units-progress')).toBeInTheDocument();
    expect(screen.getByText('SHP-50-SU1')).toBeInTheDocument();
    expect(screen.queryByTestId('packed-summary')).not.toBeInTheDocument();
    expect(screen.getByTestId('pack-form')).toBeInTheDocument();
  });

  it('opens packing once per ready selection identity, re-fires for a different selection', () => {
    const { rerender } = render(<PackDetail selection={readySelection} canWrite />);
    expect(openMutate).toHaveBeenCalledTimes(1);
    expect(openMutate).toHaveBeenCalledWith(101);

    // Re-render with a NEW object but the SAME selection identity -- must not re-fire.
    rerender(<PackDetail selection={{ mode: 'ready', pickOrderId: 1, deliveryOrderId: 101 }} canWrite />);
    expect(openMutate).toHaveBeenCalledTimes(1);

    // A different ready selection -- must fire again.
    rerender(<PackDetail selection={{ mode: 'ready', pickOrderId: 2, deliveryOrderId: 102 }} canWrite />);
    expect(openMutate).toHaveBeenCalledTimes(2);
    expect(openMutate).toHaveBeenLastCalledWith(102);
  });

  it('pack confirm posts weight/type and shows the packed summary', () => {
    render(<PackDetail selection={readySelection} canWrite />);
    fireEvent.change(document.getElementById('pack-weight')!, { target: { value: '2.5' } });
    fireEvent.click(screen.getByTestId('pack-confirm-btn'));
    expect(packMutate).toHaveBeenCalledWith({
      shipmentId: openedShipment.id,
      body: { weight: 2.5, type: 'CARTON' },
    });
  });

  it('shows the packing hint from the delivery order', () => {
    deliveryOrderData = { packingHint: 'Double box' };
    render(<PackDetail selection={readySelection} canWrite />);
    const hint = screen.getByTestId('pack-pane-hint');
    expect(hint).toBeInTheDocument();
    expect(hint).toHaveTextContent('Double box');
  });

  it('shows no hint card when the delivery order has no packingHint', () => {
    deliveryOrderData = { packingHint: null };
    render(<PackDetail selection={readySelection} canWrite />);
    expect(screen.queryByTestId('pack-pane-hint')).not.toBeInTheDocument();
  });

  // I-1 (final review): packShipment's mutation result persists across selection
  // changes (the pane stays mounted), so packing order A then selecting a different
  // ready row B rendered A's packed summary under B. Guarded by deliveryOrderId.
  it('drops a stale pack result when a different selection is chosen (I-1)', () => {
    packData = packedShipment; // A's (DO-101) pack mutation resolved -> PACKED
    const { rerender } = render(<PackDetail selection={readySelection} canWrite />);
    expect(screen.getByTestId('packed-summary')).toBeInTheDocument();

    // Switch to a different ready selection (B, DO-102) which hasn't been opened yet.
    openedShipmentData = undefined;
    rerender(
      <PackDetail selection={{ mode: 'ready', pickOrderId: 2, deliveryOrderId: 102 }} canWrite />,
    );

    // Without the guard, packShipment.data (still A's PACKED shipment, DO-101) would
    // win and render A's packed-summary under B's selection.
    expect(screen.queryByTestId('packed-summary')).not.toBeInTheDocument();
  });

  // Bulk Allocation Sprint C follow-up: two in-progress GROUP shipments both carry
  // deliveryOrderId: null (they're packed from a consolidation group's carts, not a single
  // delivery order), so the I-1 guard above must key on the shipment's own id, not
  // deliveryOrderId, or A's stale pack result would match B purely on null === null.
  it('drops a stale pack result across two in-progress GROUP shipments sharing deliveryOrderId: null', () => {
    const groupA: Shipment = {
      id: 60, shipmentNumber: 'SHP-60', deliveryOrderId: null, deliveryOrderNumber: null,
      orders: [{ id: 101, number: 'DO-101' }], state: 650,
      shippingUnits: [
        { id: 600, shippingUnitNumber: 'SHP-60-SU1', type: 'CARTON', weight: 3, state: 650, unitLoadId: 9, positionIndex: 1, origin: 'PACKOUT' },
      ],
    };
    packData = groupA; // group A's pack mutation resolved -> PACKED
    const selectionA: PackSelection = { mode: 'inProgress', shipmentId: 60, deliveryOrderId: null };
    const selectionB: PackSelection = { mode: 'inProgress', shipmentId: 61, deliveryOrderId: null };

    const { rerender } = render(<PackDetail selection={selectionA} canWrite />);
    expect(screen.getByTestId('packed-summary')).toBeInTheDocument();
    expect(screen.getByText('SHP-60-SU1')).toBeInTheDocument();

    // Switch to a DIFFERENT group shipment (B) that hasn't been fetched yet.
    freshShipmentData = undefined;
    rerender(<PackDetail selection={selectionB} canWrite />);

    // Without the identity fix, packShipment.data (still A's, deliveryOrderId: null) would
    // match B's selection (also deliveryOrderId: null) and leak A's packed summary onto B.
    expect(screen.queryByTestId('packed-summary')).not.toBeInTheDocument();
    expect(screen.queryByText('SHP-60-SU1')).not.toBeInTheDocument();
  });

  // A group selection's null deliveryOrderId must not fall back to matching the first
  // coexisting EXTINGUISH pick order (deliveryOrderId: null too, see the pickOrders fixture
  // above) -- a group shipment shows no per-order contents in v1 (documented in pack-detail.tsx).
  it('shows no contents row for a group selection even with a coexisting EXTINGUISH pick order (deliveryOrderId: null)', () => {
    const groupSelection: PackSelection = { mode: 'inProgress', shipmentId: 62, deliveryOrderId: null };
    freshShipmentData = {
      id: 62, shipmentNumber: 'SHP-62', deliveryOrderId: null, deliveryOrderNumber: null,
      orders: [{ id: 105, number: 'DO-105' }], state: 640, shippingUnits: [],
    };
    render(<PackDetail selection={groupSelection} canWrite />);

    expect(screen.getByTestId('pack-contents')).toBeInTheDocument();
    expect(screen.queryByText('SKU-EXT')).not.toBeInTheDocument();
    expect(screen.queryByTestId('pack-content-14')).not.toBeInTheDocument();
  });
});
