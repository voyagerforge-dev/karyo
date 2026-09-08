import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import type { Shipment, ShippingUnit } from '@/types/shipments';
import type { DeliveryOrderResponse } from '@/types/orders';
import { ShipDetail } from '../ship-detail';

const manifestMutate = vi.fn();
const dispatchMutate = vi.fn();
const claimMutate = vi.fn();
const releaseMutate = vi.fn();
const pauseMutate = vi.fn();
const resumeMutate = vi.fn();
const cancelMutate = vi.fn();
const removeUnitMutate = vi.fn();
const addAdHocMutate = vi.fn();
// Mutable per-test fixtures. Referenced only inside the factory's returned
// functions, which run at call time (after hoisting), so `let` is safe here
// (mirrors pack-detail.test.tsx).
let manifestData: Shipment | undefined;
let dispatchData: Shipment | undefined;
let claimData: Shipment | undefined;
let releaseData: Shipment | undefined;
let pauseData: Shipment | undefined;
let resumeData: Shipment | undefined;
let cancelData: Shipment | undefined;
let removeUnitData: Shipment | undefined;
let addAdHocData: Shipment | undefined;
let freshShipmentData: Shipment | undefined;
let deliveryOrderData: Partial<DeliveryOrderResponse> | undefined;

vi.mock('../use-shipping', () => ({
  useManifest: () => ({ mutate: manifestMutate, isPending: false, data: manifestData }),
  useDispatch: () => ({ mutate: dispatchMutate, isPending: false, data: dispatchData }),
  useShipment: vi.fn(() => ({ data: freshShipmentData, isLoading: false })),
  useClaimShipment: () => ({ mutate: claimMutate, isPending: false, data: claimData }),
  useReleaseShipment: () => ({ mutate: releaseMutate, isPending: false, data: releaseData }),
  usePauseShipment: () => ({ mutate: pauseMutate, isPending: false, data: pauseData }),
  useResumeShipment: () => ({ mutate: resumeMutate, isPending: false, data: resumeData }),
  useCancelShipment: () => ({ mutate: cancelMutate, isPending: false, data: cancelData }),
  useRemoveShippingUnit: () => ({ mutate: removeUnitMutate, isPending: false, data: removeUnitData }),
  useAddAdHocUnit: () => ({ mutate: addAdHocMutate, isPending: false, data: addAdHocData }),
}));
vi.mock('@/pages/orders/use-orders', () => ({
  useDeliveryOrder: vi.fn(() => ({ data: deliveryOrderData, isLoading: false })),
}));
let mockUserName: string | undefined = 'op-alice';
vi.mock('@/components/auth/auth-provider', () => ({
  useAuth: () => ({ userName: mockUserName }),
}));
let mockRoles: string[] = [];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: () => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  }),
}));
const viewPdf = vi.fn();
const saveZpl = vi.fn();
const mockDownloadDocument = vi.fn();
// NOTE: the module lives at `@/lib/document-actions` (moved there in D12 —
// see the file's own header comment); mocking that specifier is what
// actually intercepts `ship-detail.tsx`'s import. `archiveDocument` is left
// as the REAL implementation (not stubbed like viewPdf/saveZpl) so the
// Archive-button tests below exercise the actual `?store=true` URL-building
// logic end-to-end, down to the mocked `downloadDocument` call.
vi.mock('@/lib/document-actions', async (importOriginal) => {
  const actual = await importOriginal<typeof import('@/lib/document-actions')>();
  return {
    ...actual,
    viewPdf: (u: string) => viewPdf(u),
    saveZpl: (u: string, f: string) => saveZpl(u, f),
  };
});
vi.mock('@/lib/api-client', () => ({
  downloadDocument: (...args: unknown[]) => mockDownloadDocument(...args),
}));
vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }),
}));

const base = {
  id: 50,
  shipmentNumber: 'SHP-50',
  deliveryOrderId: 101,
  deliveryOrderNumber: 'DO-101',
  shippingUnits: [
    { id: 500, shippingUnitNumber: 'SHP-50-SU1', type: 'CARTON', weight: 2.5, state: 650, unitLoadId: 9, positionIndex: 1, origin: 'PACKOUT' },
  ] as ShippingUnit[],
};
const packing: Shipment = { ...base, state: 640 };
const packed: Shipment = { ...base, state: 650 };
const shipping: Shipment = { ...base, state: 670, carrierName: 'UPS', trackingNumber: 'MAN-SHP-50' };
const shipped: Shipment = { ...base, state: 680, carrierName: 'UPS', trackingNumber: 'MAN-SHP-50' };

beforeEach(() => {
  vi.clearAllMocks();
  viewPdf.mockClear();
  saveZpl.mockClear();
  mockDownloadDocument.mockResolvedValue(new Blob(['x'], { type: 'application/pdf' }));
  manifestData = undefined;
  dispatchData = undefined;
  claimData = undefined;
  releaseData = undefined;
  pauseData = undefined;
  resumeData = undefined;
  cancelData = undefined;
  removeUnitData = undefined;
  addAdHocData = undefined;
  freshShipmentData = undefined;
  deliveryOrderData = undefined;
  mockUserName = 'op-alice';
  mockRoles = [];
});

describe('ShipDetail', () => {
  it('PACKED shows the manifest form (write)', () => {
    freshShipmentData = packed;
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByTestId('ship-detail')).toBeInTheDocument();
    expect(screen.getByTestId('manifest-form')).toBeInTheDocument();
  });

  it('SHIPPING shows dispatch + document buttons', () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByTestId('dispatch-btn')).toBeInTheDocument();
    expect(screen.getByTestId('doc-bol')).toBeInTheDocument();
    expect(screen.getByTestId('doc-label-500')).toBeInTheDocument();
    expect(screen.getByTestId('doc-packet-list-btn')).toBeInTheDocument();
    expect(screen.getByTestId('doc-content-list-btn-500')).toBeInTheDocument();
  });

  it('SHIPPED shows the summary + docs, no dispatch', () => {
    freshShipmentData = shipped;
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('dispatch-btn')).not.toBeInTheDocument();
    expect(screen.getByTestId('doc-bol')).toBeInTheDocument();
    expect(screen.getByText(/MAN-SHP-50/)).toBeInTheDocument();
  });

  it('PACKING points to the Packing page (no form)', () => {
    freshShipmentData = packing;
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByText(/finish packing on the packing page/i)).toBeInTheDocument();
    expect(screen.queryByTestId('manifest-form')).not.toBeInTheDocument();
  });

  it('CANCELED (state 800) shows the Canceled status pill and a restoration note', () => {
    freshShipmentData = { ...base, state: 800 };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByText('Canceled')).toBeInTheDocument();
    expect(screen.getByText('Shipment canceled')).toBeInTheDocument();
    expect(screen.getByText(/stock was restored/i)).toBeInTheDocument();
  });

  it('hides manifest form without write permission', () => {
    freshShipmentData = packed;
    render(<ShipDetail shipmentId={50} canWrite={false} />);
    expect(screen.queryByTestId('manifest-form')).not.toBeInTheDocument();
    expect(screen.getByText(/awaiting manifest/i)).toBeInTheDocument();
  });

  it('ignores a stale mutation result from a different shipment (shows the selected one)', () => {
    // manifest.data is shipment 50 (SHIPPING) but the pane is showing shipment 99 (PACKED):
    // the id guard must prefer the fresh fetch, so 99's PACKED manifest form shows -- NOT
    // 50's SHIPPING view.
    manifestData = {
      id: 50,
      shipmentNumber: 'SHP-50',
      deliveryOrderId: 101,
      deliveryOrderNumber: 'DO-101',
      state: 670,
      carrierName: 'UPS',
      trackingNumber: 'MAN-SHP-50',
      shippingUnits: [],
    };
    freshShipmentData = {
      id: 99,
      shipmentNumber: 'SHP-99',
      deliveryOrderId: 109,
      deliveryOrderNumber: 'DO-109',
      state: 650,
      shippingUnits: [],
    };
    render(<ShipDetail shipmentId={99} canWrite />);
    expect(screen.getByTestId('manifest-form')).toBeInTheDocument();
    expect(screen.queryByTestId('dispatch-btn')).not.toBeInTheDocument();
  });

  it('shows the shipping hint from the delivery order', () => {
    freshShipmentData = packed;
    deliveryOrderData = { shippingHint: 'Liftgate required' };
    render(<ShipDetail shipmentId={50} canWrite />);
    const hint = screen.getByTestId('ship-pane-hint');
    expect(hint).toBeInTheDocument();
    expect(hint).toHaveTextContent('Liftgate required');
  });

  it('shows no hint card when the delivery order has no shippingHint', () => {
    freshShipmentData = packed;
    deliveryOrderData = { shippingHint: null };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-pane-hint')).not.toBeInTheDocument();
  });

  it('manifest posts carrier/service/tracking and dispatch follows', () => {
    // 650 fixture -> manifest form -> submit -> manifest.mutate({shipmentId, body}).
    freshShipmentData = packed;
    const { rerender } = render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.change(screen.getByLabelText(/service/i), { target: { value: 'GROUND' } });
    fireEvent.click(screen.getByTestId('manifest-confirm-btn'));
    expect(manifestMutate).toHaveBeenCalledWith({
      shipmentId: 50,
      body: { carrierName: 'MANUAL', carrierService: 'GROUND', trackingNumber: undefined },
    });

    // 670 fixture (post-manifest) -> dispatch-btn -> documents visible.
    freshShipmentData = shipping;
    rerender(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByTestId('dispatch-btn')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('dispatch-btn'));
    expect(dispatchMutate).toHaveBeenCalledWith(50);
    expect(screen.getByTestId('documents')).toBeInTheDocument();
  });
});

describe('ShipDetail — Task 8 packet-list + per-unit content-list document buttons', () => {
  it('requests the shipment-level packet-list PDF for this shipment id when clicked', () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('doc-packet-list-btn'));
    expect(viewPdf).toHaveBeenCalledWith('/api/v1/shipments/50/packet-list.pdf');
  });

  it('requests the per-unit content-list PDF for each shipping unit id when clicked', () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('doc-content-list-btn-500'));
    expect(viewPdf).toHaveBeenCalledWith('/api/v1/shipping-units/500/content-list.pdf');
  });

  it('downloads the ZPL label named after the shipping-unit number when clicked', () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('doc-label-500'));
    expect(saveZpl).toHaveBeenCalledWith('/api/v1/shipping-units/500/label.zpl', 'SHP-50-SU1.zpl');
  });
});

describe('ShipDetail — Task 4 Archive actions (D13 documents archive)', () => {
  it('archives the BOL with ?store=true appended when Archive is clicked', async () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('doc-bol-archive'));
    await waitFor(() =>
      expect(mockDownloadDocument).toHaveBeenCalledWith('/api/v1/shipments/50/bol.pdf?store=true'),
    );
  });

  it('archives the packing slip with ?store=true appended when Archive is clicked', async () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('doc-slip-archive'));
    await waitFor(() =>
      expect(mockDownloadDocument).toHaveBeenCalledWith(
        '/api/v1/shipments/50/packing-slip.pdf?store=true',
      ),
    );
  });

  it('archives the shipment-level packet list with ?store=true appended', async () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('doc-packet-list-archive-btn'));
    await waitFor(() =>
      expect(mockDownloadDocument).toHaveBeenCalledWith(
        '/api/v1/shipments/50/packet-list.pdf?store=true',
      ),
    );
  });

  it('archives the per-unit label with ?store=true appended', async () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('doc-label-archive-500'));
    await waitFor(() =>
      expect(mockDownloadDocument).toHaveBeenCalledWith(
        '/api/v1/shipping-units/500/label.zpl?store=true',
      ),
    );
  });

  it('archives the per-unit content list with ?store=true appended', async () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('doc-content-list-archive-btn-500'));
    await waitFor(() =>
      expect(mockDownloadDocument).toHaveBeenCalledWith(
        '/api/v1/shipping-units/500/content-list.pdf?store=true',
      ),
    );
  });
});

describe('ShipDetail -- Task 9 lifecycle button gating', () => {
  it('hides claim/pause entirely without write permission', () => {
    freshShipmentData = { ...packed, operatorId: null, pausedAt: null };
    render(<ShipDetail shipmentId={50} canWrite={false} />);
    expect(screen.queryByTestId('ship-claim-button')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ship-pause-button')).not.toBeInTheDocument();
  });

  it('shows Claim for an unclaimed, unpaused, open shipment and claims on click', () => {
    freshShipmentData = { ...packed, operatorId: null, pausedAt: null };
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('ship-claim-button'));
    expect(claimMutate).toHaveBeenCalledWith(50);
  });

  it('hides Claim once claimed, showing "Claimed by" instead', () => {
    freshShipmentData = { ...packed, operatorId: 'op-bob' };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-claim-button')).not.toBeInTheDocument();
    expect(screen.getByText(/claimed by op-bob/i)).toBeInTheDocument();
  });

  it('shows Release for the owning operator and releases on click', () => {
    freshShipmentData = { ...packed, operatorId: 'op-alice' };
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('ship-release-button'));
    expect(releaseMutate).toHaveBeenCalledWith(50);
  });

  it('offers a manager release when claimed by a different operator and the caller is MANAGER', () => {
    mockRoles = ['MANAGER'];
    freshShipmentData = { ...packed, operatorId: 'op-bob' };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByTestId('ship-release-button')).toHaveTextContent(/manager/i);
  });

  it('does not offer manager release without the MANAGER role', () => {
    freshShipmentData = { ...packed, operatorId: 'op-bob' };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-release-button')).not.toBeInTheDocument();
  });

  it('hides Claim once SHIPPED (state >= 680)', () => {
    freshShipmentData = { ...shipped, operatorId: null };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-claim-button')).not.toBeInTheDocument();
  });

  it('shows Pause on an open, unpaused shipment and pauses on click', () => {
    freshShipmentData = { ...packed, pausedAt: null };
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('ship-pause-button'));
    expect(pauseMutate).toHaveBeenCalledWith(50);
  });

  it('hides Claim and Pause while paused', () => {
    freshShipmentData = { ...packed, operatorId: null, pausedAt: '2026-08-15T10:00:00Z' };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-claim-button')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ship-pause-button')).not.toBeInTheDocument();
  });

  it('shows Cancel pre-manifest (PACKING/PACKED) and hides it once SHIPPING', () => {
    freshShipmentData = packing;
    const { rerender } = render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByTestId('ship-cancel-button')).toBeInTheDocument();

    freshShipmentData = shipping;
    rerender(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-cancel-button')).not.toBeInTheDocument();
  });
});

describe('ShipDetail -- Task 9 paused banner + resume', () => {
  it('shows the paused banner with a timestamp and resumes on click', () => {
    freshShipmentData = { ...packed, pausedAt: '2026-08-15T10:00:00.000Z' };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByTestId('ship-paused-banner')).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('ship-resume-button'));
    expect(resumeMutate).toHaveBeenCalledWith(50);
  });

  it('shows no paused banner or Resume button when not paused', () => {
    freshShipmentData = { ...packed, pausedAt: null };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-paused-banner')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ship-resume-button')).not.toBeInTheDocument();
  });

  // pausedAt is never cleared by a terminal transition (cancel/dispatch), so a stale
  // pausedAt stamp can coexist with a closed state -- the banner/Resume must gate on
  // state<SHIPPED too, or a CANCELED-while-paused shipment would show a live Resume
  // that silently mutates a terminal shipment (receipt-detail precedent).
  it('hides the paused banner and Resume button on a CANCELED shipment even with pausedAt set', () => {
    freshShipmentData = { ...base, state: 800, pausedAt: '2026-08-15T10:00:00.000Z' };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-paused-banner')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ship-resume-button')).not.toBeInTheDocument();
  });

  it('hides the paused banner and Resume button on a SHIPPED shipment even with pausedAt set', () => {
    freshShipmentData = { ...shipped, pausedAt: '2026-08-15T10:00:00.000Z' };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-paused-banner')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ship-resume-button')).not.toBeInTheDocument();
  });
});

describe('ShipDetail -- Task 9 cancel confirm flow', () => {
  it('opens a destructive-styled confirm dialog stating stock restoration, cancels on confirm', () => {
    freshShipmentData = packed;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('ship-cancel-button'));
    const confirmBtn = screen.getByTestId('ship-cancel-confirm-btn');
    expect(confirmBtn).toHaveAttribute('data-variant', 'destructive');
    expect(screen.getByText(/restores every shipping unit's stock/i)).toBeInTheDocument();
    fireEvent.click(confirmBtn);
    expect(cancelMutate).toHaveBeenCalledWith(50, expect.anything());
  });
});

describe('ShipDetail -- Task 9 units table (Box/carrier/tracking) + per-unit remove', () => {
  const packoutUnit: ShippingUnit = {
    id: 500,
    shippingUnitNumber: 'SHP-50-SU1',
    type: 'CARTON',
    weight: 2.5,
    state: 650,
    unitLoadId: 9,
    positionIndex: 1,
    carrierLabel: 'UPS Ground',
    trackingNumber: '1Z999',
    origin: 'PACKOUT',
  };
  const adHocUnit: ShippingUnit = {
    id: 501,
    shippingUnitNumber: 'SHP-50-SU2',
    type: 'PALLET',
    weight: 40,
    state: 650,
    unitLoadId: 21,
    positionIndex: 2,
    carrierLabel: null,
    trackingNumber: null,
    origin: 'AD_HOC',
  };

  it('shows Box/carrier label/tracking # columns and a remove button pre-manifest', () => {
    freshShipmentData = { ...packed, shippingUnits: [packoutUnit] };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.getByText('Box 1')).toBeInTheDocument();
    expect(screen.getByText('UPS Ground')).toBeInTheDocument();
    expect(screen.getByText('1Z999')).toBeInTheDocument();
    expect(screen.getByTestId('ship-unit-remove-btn-500')).toBeInTheDocument();
  });

  it('hides remove buttons once SHIPPING (post-manifest)', () => {
    freshShipmentData = { ...shipping, shippingUnits: [packoutUnit] };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ship-unit-remove-btn-500')).not.toBeInTheDocument();
  });

  it('captions a PACKOUT unit removal "back to picked" and removes on confirm', () => {
    freshShipmentData = { ...packed, shippingUnits: [packoutUnit] };
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('ship-unit-remove-btn-500'));
    expect(screen.getByText(/back to picked/i)).toBeInTheDocument();
    fireEvent.click(screen.getByTestId('ship-unit-remove-confirm-btn'));
    expect(removeUnitMutate).toHaveBeenCalledWith({ shipmentId: 50, unitId: 500 }, expect.anything());
  });

  it('captions an AD_HOC unit removal "back to stock"', () => {
    freshShipmentData = { ...packed, shippingUnits: [adHocUnit] };
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.click(screen.getByTestId('ship-unit-remove-btn-501'));
    expect(screen.getByText(/back to stock/i)).toBeInTheDocument();
  });
});

describe('ShipDetail -- Task 9 ad-hoc unit attach', () => {
  it('calls the ad-hoc mutation with the shipment id and the parsed unit load id', () => {
    freshShipmentData = packed;
    render(<ShipDetail shipmentId={50} canWrite />);
    fireEvent.change(screen.getByTestId('ad-hoc-unit-input'), { target: { value: '77' } });
    fireEvent.click(screen.getByTestId('ad-hoc-attach-btn'));
    expect(addAdHocMutate).toHaveBeenCalledWith({ shipmentId: 50, unitLoadId: 77 }, expect.anything());
  });

  it('hides the ad-hoc affordance once SHIPPING (post-manifest)', () => {
    freshShipmentData = shipping;
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ad-hoc-unit-input')).not.toBeInTheDocument();
  });

  it('hides the ad-hoc affordance while paused', () => {
    freshShipmentData = { ...packed, pausedAt: '2026-08-15T10:00:00Z' };
    render(<ShipDetail shipmentId={50} canWrite />);
    expect(screen.queryByTestId('ad-hoc-unit-input')).not.toBeInTheDocument();
  });

  it('hides the ad-hoc affordance without write permission', () => {
    freshShipmentData = packed;
    render(<ShipDetail shipmentId={50} canWrite={false} />);
    expect(screen.queryByTestId('ad-hoc-unit-input')).not.toBeInTheDocument();
  });
});
