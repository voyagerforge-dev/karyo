import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { PickOrderResponse } from '@/types/pick-orders';
import type { DeliveryOrderResponse } from '@/types/orders';
import type { WorkItemResponse } from '@/types/work';

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

const viewPdf = vi.fn();
vi.mock('@/lib/document-actions', () => ({
  viewPdf: (u: string) => viewPdf(u),
}));

const confirmMutate = vi.fn();
const cancelMutate = vi.fn();
const bulkConfirmMutate = vi.fn();
const usePickOrderMock = vi.fn();
const useBulkLinesMock = vi.fn();
vi.mock('@/features/picking/use-pick-orders', () => ({
  usePickOrder: (...args: unknown[]) => usePickOrderMock(...args),
  useConfirmPick: vi.fn(() => ({ mutate: confirmMutate, isPending: false })),
  useCancelPickOrder: vi.fn(() => ({ mutate: cancelMutate, isPending: false })),
  useBulkLines: (...args: unknown[]) => useBulkLinesMock(...args),
  useBulkConfirm: vi.fn(() => ({ mutate: bulkConfirmMutate, isPending: false })),
}));

const useDeliveryOrderMock = vi.fn();
vi.mock('@/pages/orders/use-orders', () => ({
  useDeliveryOrder: (...args: unknown[]) => useDeliveryOrderMock(...args),
}));

let mockRoles = ['task-read', 'task-write', 'fulfillment-read', 'fulfillment-write'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

import { PickPane } from '../pick-pane';

const pickOrderFixture: PickOrderResponse = {
  id: 42,
  pickOrderNumber: 'PO-100',
  deliveryOrderId: 9,
  deliveryOrderNumber: 'DO-500',
  state: 500,
  targetUnitLoadId: null,
  weight: 12.5,
  volume: 0.75,
  destinationLocationId: null,
  bulk: false,
  autoOpenPending: false,
  picks: [
    {
      id: 1,
      deliveryOrderLineId: 1,
      itemDataId: 1,
      itemDataNumber: 'SKU-1',
      sourceStockUnitId: 11,
      plannedAmount: 10,
      pickedAmount: 0,
      state: 100,
      lotNumber: 'LOT-PLANNED-1',
      pickingType: 'PICK',
      followUpForPickId: null,
      substitutedItemDataId: null,
      pickedLotNumber: null,
      pickedBestBefore: null,
    },
    {
      id: 2,
      deliveryOrderLineId: 2,
      itemDataId: 2,
      itemDataNumber: 'SKU-2',
      sourceStockUnitId: 12,
      plannedAmount: 20,
      pickedAmount: 15,
      state: 600,
      lotNumber: null,
      pickingType: 'PICK',
      followUpForPickId: null,
      substitutedItemDataId: null,
      pickedLotNumber: 'LOT-ACTUAL-2',
      pickedBestBefore: '2026-09-01',
    },
    {
      id: 3,
      deliveryOrderLineId: 1,
      itemDataId: 3,
      itemDataNumber: 'SKU-3',
      sourceStockUnitId: 13,
      plannedAmount: 5,
      pickedAmount: 5,
      state: 600,
      lotNumber: null,
      pickingType: 'PICK',
      followUpForPickId: null,
      substitutedItemDataId: 1,
      pickedLotNumber: null,
      pickedBestBefore: null,
    },
  ],
};

const deliveryOrderFixture: DeliveryOrderResponse = {
  id: 9,
  orderNumber: 'DO-500',
  externalNumber: null,
  customerName: null,
  street: null,
  streetNumber: null,
  zipCode: null,
  city: null,
  country: null,
  phone: null,
  email: null,
  deliveryDate: null,
  prio: 50,
  notes: null,
  pickingHint: null,
  packingHint: null,
  shippingHint: null,
  state: 400,
  stateName: 'RELEASED',
  orderStrategyId: null,
  clientId: 1,
  lines: [],
  created: '2026-06-13T09:00:00Z',
  modified: '2026-06-13T09:00:00Z',
  carrierName: null,
  carrierService: null,
  trackingNumber: null,
  shippedAt: null,
  destinationLocationId: null,
  destinationLocationName: null,
  operatorId: null,
  senderName: null,
  documentUrl: '/api/v1/delivery-orders/9/delivery-note.pdf',
  labelUrl: null,
};

const workItem: WorkItemResponse = {
  ref: 'PICK:42',
  workType: 'PICK',
  priority: 20,
  state: 'CLAIMED',
  claimedBy: 'op-bob',
  zone: 'A',
  primaryLocation: 'PICK-01',
  destination: null,
  summary: 'Pick order PO-100',
  createdAt: '2026-06-13T09:05:00Z',
};

function renderPane() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <PickPane workItem={workItem} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  confirmMutate.mockClear();
  cancelMutate.mockClear();
  bulkConfirmMutate.mockClear();
  viewPdf.mockClear();
  usePickOrderMock.mockReset();
  usePickOrderMock.mockReturnValue({ data: pickOrderFixture, isLoading: false });
  useDeliveryOrderMock.mockReset();
  useDeliveryOrderMock.mockReturnValue({ data: deliveryOrderFixture, isLoading: false });
  useBulkLinesMock.mockReset();
  useBulkLinesMock.mockReturnValue({ data: undefined });
});

afterEach(() => {
  mockRoles = ['task-read', 'task-write', 'fulfillment-read', 'fulfillment-write'];
});

describe('PickPane', () => {
  it('renders the picks table with per-pick badges and confirm buttons', () => {
    renderPane();

    expect(screen.getByTestId('picks-table')).toBeInTheDocument();

    const openRow = screen.getByTestId('pick-row-1');
    expect(within(openRow).getByTestId('pick-confirm-btn-1')).toBeInTheDocument();

    const shortRow = screen.getByTestId('pick-row-2');
    expect(within(shortRow).getByText('short')).toBeInTheDocument();
    expect(within(shortRow).queryByTestId('pick-confirm-btn-2')).not.toBeInTheDocument();

    const subRow = screen.getByTestId('pick-row-3');
    expect(within(subRow).getByText('sub')).toBeInTheDocument();
  });

  it('confirm flow posts pickedAmount + optional target UL', async () => {
    const user = userEvent.setup();
    renderPane();

    await user.click(screen.getByTestId('pick-confirm-btn-1'));
    expect(await screen.findByTestId('pick-confirm-form')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /confirm pick/i }));

    expect(confirmMutate).toHaveBeenCalledWith(
      { pickId: 1, body: { pickedAmount: 10, targetUnitLoadId: undefined } },
      expect.anything(),
    );
  });

  it('shows the order pickingHint when set', () => {
    useDeliveryOrderMock.mockReturnValue({
      data: { ...deliveryOrderFixture, pickingHint: 'Fragile' },
      isLoading: false,
    });
    renderPane();

    expect(screen.getByText('Fragile')).toBeInTheDocument();
  });

  it('gates the domain fetch on fulfillment-read with an honest message', () => {
    mockRoles = ['task-read', 'task-write'];
    usePickOrderMock.mockReturnValue({ data: undefined, isLoading: false });
    renderPane();

    expect(usePickOrderMock).toHaveBeenCalledWith(undefined);
    expect(
      screen.getByText(/need fulfillment access to view pick details/i),
    ).toBeInTheDocument();
    expect(screen.queryByTestId('picks-table')).not.toBeInTheDocument();
  });

  it('shows the Pick ticket document button without requiring fulfillment-write', () => {
    mockRoles = ['task-read', 'fulfillment-read']; // no fulfillment-write
    renderPane();
    expect(screen.getByTestId('doc-pick-ticket-btn')).toBeInTheDocument();
  });

  it('requests the pick-ticket PDF for this pick order id when clicked', async () => {
    const user = userEvent.setup();
    renderPane();
    await user.click(screen.getByTestId('doc-pick-ticket-btn'));
    expect(viewPdf).toHaveBeenCalledWith('/api/v1/pick-orders/42/pick-ticket.pdf');
  });

  it('renders order-level weight/volume in the header meta', () => {
    renderPane();
    expect(screen.getByTestId('pick-order-weight')).toHaveTextContent('12.5 kg');
    expect(screen.getByTestId('pick-order-volume')).toHaveTextContent('0.75 m³');
  });

  it('renders "—" for weight/volume when null', () => {
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, weight: null, volume: null },
      isLoading: false,
    });
    renderPane();
    expect(screen.getByTestId('pick-order-weight')).toHaveTextContent('—');
    expect(screen.getByTestId('pick-order-volume')).toHaveTextContent('—');
  });

  it('renders "—" for the order number when the pick order has no backing delivery order (EXT-)', () => {
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, deliveryOrderId: null, deliveryOrderNumber: null },
      isLoading: false,
    });
    renderPane();
    expect(screen.getByText('Order —')).toBeInTheDocument();
  });

  it('renders the picked lot + best-before once confirmed, falling back to the planned lot pre-confirm', () => {
    renderPane();
    // Row 1 (state 100, not yet confirmed) -> planned lot, no best-before.
    expect(screen.getByTestId('pick-lot-1')).toHaveTextContent('LOT-PLANNED-1');
    // Row 2 (confirmed) -> actual picked lot + best-before, not the planned one.
    expect(screen.getByTestId('pick-lot-2')).toHaveTextContent('LOT-ACTUAL-2');
    expect(screen.getByTestId('pick-lot-2')).toHaveTextContent('2026-09-01');
    // Row 3 -> neither planned nor picked lot -> honest "—".
    expect(screen.getByTestId('pick-lot-3')).toHaveTextContent('—');
  });
});

describe('PickPane -- auto-open pending notice (row :1470, A8)', () => {
  it('renders the notice when autoOpenPending is true', () => {
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, autoOpenPending: true },
      isLoading: false,
    });
    renderPane();
    expect(screen.getByTestId('pick-pane-auto-open-pending')).toBeInTheDocument();
    expect(
      screen.getByText(
        /automatic shipment opening did not complete; open packing manually or re-check the strategy/i,
      ),
    ).toBeInTheDocument();
  });

  it('does not render the notice when autoOpenPending is false', () => {
    renderPane(); // fixture carries autoOpenPending: false
    expect(screen.queryByTestId('pick-pane-auto-open-pending')).not.toBeInTheDocument();
  });

  it('dismisses the notice locally when Dismiss is clicked', async () => {
    const user = userEvent.setup();
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, autoOpenPending: true },
      isLoading: false,
    });
    renderPane();

    expect(screen.getByTestId('pick-pane-auto-open-pending')).toBeInTheDocument();
    await user.click(screen.getByTestId('pick-pane-auto-open-pending-dismiss'));
    expect(screen.queryByTestId('pick-pane-auto-open-pending')).not.toBeInTheDocument();
  });

  // Review fix: WorkDetailPane renders PickPane unkeyed across different work items, so
  // dismissal state previously leaked across pick orders (a plain useState survives a prop
  // change on the same mounted component). These two tests exercise the fix directly by
  // rerendering the SAME mounted tree (not remounting via a fresh renderPane() call).
  it('does not leak a dismissal across a different pick order in the same unkeyed pane', async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, id: 42, autoOpenPending: true },
      isLoading: false,
    });
    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <PickPane workItem={workItem} />
      </QueryClientProvider>,
    );

    expect(screen.getByTestId('pick-pane-auto-open-pending')).toBeInTheDocument();
    await user.click(screen.getByTestId('pick-pane-auto-open-pending-dismiss'));
    expect(screen.queryByTestId('pick-pane-auto-open-pending')).not.toBeInTheDocument();

    // Same mounted pane, now showing a DIFFERENT pick order (id 99) that is also pending.
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, id: 99, autoOpenPending: true },
      isLoading: false,
    });
    rerender(
      <QueryClientProvider client={queryClient}>
        <PickPane workItem={{ ...workItem, ref: 'PICK:99', summary: 'Pick order PO-200' }} />
      </QueryClientProvider>,
    );

    expect(screen.getByTestId('pick-pane-auto-open-pending')).toBeInTheDocument();
  });

  it('re-arms the notice for the SAME order after it clears then goes pending again', async () => {
    const user = userEvent.setup();
    const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, autoOpenPending: true },
      isLoading: false,
    });
    const { rerender } = render(
      <QueryClientProvider client={queryClient}>
        <PickPane workItem={workItem} />
      </QueryClientProvider>,
    );

    expect(screen.getByTestId('pick-pane-auto-open-pending')).toBeInTheDocument();
    await user.click(screen.getByTestId('pick-pane-auto-open-pending-dismiss'));
    expect(screen.queryByTestId('pick-pane-auto-open-pending')).not.toBeInTheDocument();

    // Clears (e.g. a manual packing open succeeded) -- stays hidden, as expected.
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, autoOpenPending: false },
      isLoading: false,
    });
    rerender(
      <QueryClientProvider client={queryClient}>
        <PickPane workItem={workItem} />
      </QueryClientProvider>,
    );
    expect(screen.queryByTestId('pick-pane-auto-open-pending')).not.toBeInTheDocument();

    // Re-arms true again for the SAME order id -- must reappear, not stay suppressed by the
    // earlier dismissal.
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, autoOpenPending: true },
      isLoading: false,
    });
    rerender(
      <QueryClientProvider client={queryClient}>
        <PickPane workItem={workItem} />
      </QueryClientProvider>,
    );
    expect(screen.getByTestId('pick-pane-auto-open-pending')).toBeInTheDocument();
  });
});

describe('PickPane — cancel (row 14)', () => {
  it('shows the Cancel button for a pre-PICKED order when fulfillment-write is held', () => {
    renderPane(); // fixture state 500 (STARTED), mockRoles carries fulfillment-write
    expect(screen.getByTestId('pick-order-cancel-btn')).toBeInTheDocument();
  });

  it('hides the Cancel button without fulfillment-write', () => {
    mockRoles = ['task-read', 'task-write', 'fulfillment-read'];
    renderPane();
    expect(screen.queryByTestId('pick-order-cancel-btn')).not.toBeInTheDocument();
  });

  it('hides the Cancel button once the order has reached PICKED(600)', () => {
    usePickOrderMock.mockReturnValue({
      data: { ...pickOrderFixture, state: 600 },
      isLoading: false,
    });
    renderPane();
    expect(screen.queryByTestId('pick-order-cancel-btn')).not.toBeInTheDocument();
  });

  it('opens a confirm dialog and cancels the order on confirm', async () => {
    const user = userEvent.setup();
    renderPane();

    await user.click(screen.getByTestId('pick-order-cancel-btn'));
    expect(screen.getByText('Cancel pick order?')).toBeInTheDocument();
    expect(
      screen.getByText('Open picks are canceled and reserved stock is released.'),
    ).toBeInTheDocument();

    await user.click(screen.getByTestId('pick-order-cancel-confirm-btn'));
    expect(cancelMutate).toHaveBeenCalledWith(42, expect.anything());
  });
});

describe('PickPane -- bulk pick order (Sprint B)', () => {
  const bulkLine = {
    sourceStockUnitId: 3,
    locationName: 'A-01-01',
    unitLoadLabel: 'UL-1',
    itemDataId: 5,
    itemDataNumber: 'SKU-1',
    lotNumber: null,
    plannedTotal: 40,
    pickedTotal: 0,
    openSlices: 3,
  };

  it('renders the bulk-lines pane alongside a read-only picks table for a bulk order', () => {
    usePickOrderMock.mockReturnValue({ data: { ...pickOrderFixture, bulk: true }, isLoading: false });
    useBulkLinesMock.mockReturnValue({ data: [bulkLine] });
    renderPane();

    const bulkTable = screen.getByTestId('bulk-lines-table');
    expect(within(bulkTable).getByText('SKU-1')).toBeInTheDocument();
    expect(within(bulkTable).getByText('40')).toBeInTheDocument();
    expect(within(bulkTable).getByText('3 orders')).toBeInTheDocument();
    expect(within(bulkTable).getByRole('button', { name: 'Confirm' })).toBeInTheDocument();

    // Ruling 9: the per-pick table stays visible for a bulk order too, as a
    // read-only audit record -- no Confirm column, no confirm form.
    const picksTable = screen.getByTestId('picks-table');
    expect(within(picksTable).queryByTestId('pick-confirm-btn-1')).not.toBeInTheDocument();
    expect(within(picksTable).queryByRole('button', { name: 'Confirm' })).not.toBeInTheDocument();
  });

  it('fan-out confirms a bulk line with the entered picked amount', async () => {
    const user = userEvent.setup();
    usePickOrderMock.mockReturnValue({ data: { ...pickOrderFixture, bulk: true }, isLoading: false });
    useBulkLinesMock.mockReturnValue({ data: [bulkLine] });
    renderPane();

    await user.click(screen.getByRole('button', { name: 'Confirm' }));
    const form = await screen.findByTestId('pick-confirm-form');
    const qtyInput = within(form).getByLabelText('Picked qty');
    await user.clear(qtyInput);
    await user.type(qtyInput, '27');
    await user.click(within(form).getByRole('button', { name: /confirm pick/i }));

    expect(bulkConfirmMutate).toHaveBeenCalledWith(
      { pickOrderId: 42, body: { sourceStockUnitId: 3, pickedAmount: 27, targetUnitLoadId: undefined } },
      expect.anything(),
    );
  });

  it('shows an error row when the bulk-lines query fails, without the empty-state message', () => {
    usePickOrderMock.mockReturnValue({ data: { ...pickOrderFixture, bulk: true }, isLoading: false });
    useBulkLinesMock.mockReturnValue({ isError: true, error: new Error('boom'), data: undefined });
    renderPane();

    const table = screen.getByTestId('bulk-lines-table');
    expect(within(table).getByText('boom')).toBeInTheDocument();
    expect(within(table).queryByText('All bulk lines picked.')).not.toBeInTheDocument();
  });
});
