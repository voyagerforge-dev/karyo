import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { Shipment } from '@/types/shipments';
import { ShipmentsPage } from '../shipments-page';
import { useShipments } from '../use-shipping';

vi.mock('@/lib/api-client', () => ({ api: { get: vi.fn(), post: vi.fn() } }));
vi.mock('../use-shipping', () => ({
  useShipments: vi.fn(),
  useShipment: vi.fn(() => ({ data: undefined, isLoading: false })),
  useManifest: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
  useDispatch: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
  useClaimShipment: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
  useReleaseShipment: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
  usePauseShipment: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
  useResumeShipment: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
  useCancelShipment: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
  useRemoveShippingUnit: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
  useAddAdHocUnit: vi.fn(() => ({ mutate: vi.fn(), isPending: false, data: undefined })),
}));
vi.mock('@/pages/orders/use-orders', () => ({
  useDeliveryOrder: vi.fn(() => ({ data: undefined, isLoading: false })),
}));
vi.mock('@/components/auth/auth-provider', () => ({
  useAuth: vi.fn(() => ({ userName: 'op-alice' })),
}));
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: ['fulfillment-read', 'fulfillment-write'],
    hasPermission: (p: string) => p.startsWith('fulfillment'),
    hasAnyPermission: () => true,
  })),
}));

const shipments: Shipment[] = [
  { id: 50, shipmentNumber: 'SHP-50', deliveryOrderId: 101, deliveryOrderNumber: 'DO-101', state: 650, shippingUnits: [] },
  {
    id: 51,
    shipmentNumber: 'SHP-51',
    deliveryOrderId: 102,
    deliveryOrderNumber: 'DO-102',
    state: 680,
    carrierName: 'UPS',
    trackingNumber: 'T1',
    shippingUnits: [],
  },
  { id: 52, shipmentNumber: 'SHP-52', deliveryOrderId: 103, deliveryOrderNumber: 'DO-103', state: 640, shippingUnits: [] },
  {
    id: 53,
    shipmentNumber: 'SHP-53',
    deliveryOrderId: 104,
    deliveryOrderNumber: 'DO-104',
    state: 670,
    carrierName: 'FedEx',
    trackingNumber: 'T2',
    shippingUnits: [],
  },
  {
    id: 54,
    shipmentNumber: 'SHP-54',
    deliveryOrderId: null,
    deliveryOrderNumber: null,
    orders: [
      { id: 1, number: 'A' },
      { id: 2, number: 'B' },
    ],
    state: 640,
    shippingUnits: [],
  },
];

function renderPage(entry = '/shipments') {
  return render(
    <QueryClientProvider client={new QueryClient({ defaultOptions: { queries: { retry: false } } })}>
      <MemoryRouter initialEntries={[entry]}>
        <ShipmentsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() =>
  vi.mocked(useShipments).mockReturnValue({ data: shipments, isLoading: false } as ReturnType<
    typeof useShipments
  >),
);

describe('ShipmentsPage', () => {
  it('renders the shipments list', () => {
    renderPage();
    expect(screen.getByText('SHP-50')).toBeInTheDocument();
    expect(screen.getByText('SHP-51')).toBeInTheDocument();
    expect(screen.getByText('SHP-52')).toBeInTheDocument();
    expect(screen.getByText('SHP-53')).toBeInTheDocument();
  });

  it('shows carrier · tracking on a manifested row, nothing on an unmanifested one', () => {
    renderPage();
    expect(screen.getByText(/UPS.*T1/)).toBeInTheDocument();
    expect(screen.queryByText(/^—$/)).not.toBeInTheDocument();
  });

  it('chips filter by shipment status', async () => {
    const user = userEvent.setup();
    renderPage();

    // Default chip is "All" -- every fixture is visible.
    expect(screen.getByText('SHP-50')).toBeInTheDocument();
    expect(screen.getByText('SHP-52')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Packing' }));
    expect(screen.getByText('SHP-52')).toBeInTheDocument();
    expect(screen.queryByText('SHP-50')).not.toBeInTheDocument();
    expect(screen.queryByText('SHP-51')).not.toBeInTheDocument();
    expect(screen.queryByText('SHP-53')).not.toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Shipped' }));
    expect(screen.getByText('SHP-51')).toBeInTheDocument();
    expect(screen.queryByText('SHP-52')).not.toBeInTheDocument();
  });

  it('search filters by shipment/order number and carrier', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.type(screen.getByPlaceholderText(/search shipment/i), 'DO-102');
    expect(screen.getByText('SHP-51')).toBeInTheDocument();
    expect(screen.queryByText('SHP-50')).not.toBeInTheDocument();
  });

  it('shows a member-orders chip for a group shipment and finds it by member order number', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.getByTestId('shipment-orders-chip-54')).toHaveTextContent('2 orders');

    await user.type(screen.getByPlaceholderText(/search shipment/i), 'B');
    expect(screen.getByText('SHP-54')).toBeInTheDocument();
    expect(screen.queryByText('SHP-50')).not.toBeInTheDocument();
  });

  it('selecting a row shows the ship-detail pane', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.queryByTestId('ship-detail')).not.toBeInTheDocument();
    await user.click(screen.getByText('SHP-50'));
    expect(screen.getByTestId('ship-detail')).toBeInTheDocument();
  });

  // The wave detail's consolidation-group table links here as /shipments?shipment={id}.
  it('preselects the shipment named by the ?shipment= deep link', () => {
    renderPage('/shipments?shipment=51');
    expect(screen.getByTestId('ship-detail')).toBeInTheDocument();
    expect(screen.getByTestId('shipment-row-51')).toHaveAttribute('data-active', 'true');
  });

  it('falls back to the plain list when the deep-linked shipment is not on the page', () => {
    renderPage('/shipments?shipment=9999');
    expect(screen.queryByTestId('ship-detail')).not.toBeInTheDocument();
    expect(screen.getByText('Select a shipment')).toBeInTheDocument();
  });
});
