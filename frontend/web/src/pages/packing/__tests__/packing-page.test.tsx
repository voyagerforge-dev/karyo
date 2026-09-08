import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { Shipment } from '@/types/shipments';
import { PackingPage } from '../packing-page';
import { useReadyToPack, useShipments, type OrderBoundPickOrder } from '../use-packing';

vi.mock('@/lib/api-client', () => ({ api: { get: vi.fn(), post: vi.fn() } }));

const openMutate = vi.fn();
vi.mock('../use-packing', () => ({
  useReadyToPack: vi.fn(),
  useShipments: vi.fn(),
  useShipment: vi.fn(() => ({ data: undefined, isLoading: false })),
  useOpenPacking: vi.fn(() => ({ mutate: openMutate, isPending: false, data: undefined })),
  usePackShipment: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
}));
vi.mock('@/features/picking/use-pick-orders', () => ({ usePickOrders: vi.fn(() => ({ data: [], isLoading: false })) }));
vi.mock('@/pages/orders/use-orders', () => ({ useDeliveryOrder: vi.fn(() => ({ data: undefined, isLoading: false })) }));

let mockRoles = ['fulfillment-read', 'fulfillment-write'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

const ready: OrderBoundPickOrder[] = [{
  id: 1, pickOrderNumber: 'PO-1', deliveryOrderId: 101, deliveryOrderNumber: 'DO-101',
  state: 600, targetUnitLoadId: 9, picks: [], weight: null, volume: null, destinationLocationId: null,
  bulk: false,
  autoOpenPending: false,
}];
const inProgress: Shipment[] = [{
  id: 50, shipmentNumber: 'SHP-50', deliveryOrderId: 102, deliveryOrderNumber: 'DO-102',
  state: 640, shippingUnits: [],
}];

function renderPage() {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter><PackingPage /></MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  mockRoles = ['fulfillment-read', 'fulfillment-write'];
  openMutate.mockClear();
  vi.mocked(useReadyToPack).mockReturnValue({ data: ready, isLoading: false });
  vi.mocked(useShipments).mockReturnValue({ data: inProgress, isLoading: false } as ReturnType<typeof useShipments>);
});

describe('PackingPage', () => {
  it('lists ready-to-pack and in-progress under chips', async () => {
    const user = userEvent.setup();
    renderPage();

    // Default chip is "Ready to pack" -- the ready fixture is visible.
    expect(screen.getByText('DO-101')).toBeInTheDocument();
    expect(screen.queryByText('SHP-50')).not.toBeInTheDocument();

    // Switch to "In progress" -- the PACKING shipment fixture is visible instead.
    await user.click(screen.getByText('In progress'));
    expect(screen.getByText('SHP-50')).toBeInTheDocument();
    expect(screen.queryByText('DO-101')).not.toBeInTheDocument();
  });

  it('selecting a ready row shows the pack pane and opens packing once', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.queryByTestId('pack-form')).not.toBeInTheDocument();
    await user.click(screen.getByTestId('pack-btn-1'));

    expect(openMutate).toHaveBeenCalledTimes(1);
    expect(openMutate).toHaveBeenCalledWith(101);

    // Re-clicking the SAME row must not re-fire the open mutation.
    await user.click(screen.getByTestId('pack-btn-1'));
    expect(openMutate).toHaveBeenCalledTimes(1);
  });

  it('shows a member-orders chip for an in-progress group shipment', async () => {
    const user = userEvent.setup();
    vi.mocked(useShipments).mockReturnValue({
      data: [
        ...inProgress,
        {
          id: 55,
          shipmentNumber: 'SHP-55',
          deliveryOrderId: null,
          deliveryOrderNumber: null,
          orders: [{ id: 1, number: 'A' }, { id: 2, number: 'B' }],
          state: 640,
          shippingUnits: [],
        },
      ],
      isLoading: false,
    } as ReturnType<typeof useShipments>);
    renderPage();

    await user.click(screen.getByText('In progress'));
    expect(screen.getByTestId('shipment-orders-chip-55')).toHaveTextContent('2 orders');
  });

  it('hides pack actions without fulfillment-write', () => {
    mockRoles = ['fulfillment-read'];
    renderPage();
    expect(screen.queryByTestId('pack-btn-1')).not.toBeInTheDocument();
  });
});
