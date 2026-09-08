import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { DeliveryOrderLineResponse, DeliveryOrderResponse } from '@/types/orders';
import type { JournalEntry } from '@/features/insights/use-journals';

const viewPdf = vi.fn();
const saveZpl = vi.fn();
vi.mock('@/lib/document-actions', () => ({
  viewPdf: (u: string) => viewPdf(u),
  saveZpl: (u: string, f: string) => saveZpl(u, f),
  archiveDocument: vi.fn(),
}));

const cancelMutate = vi.fn();
const claimMutate = vi.fn();
const releaseOperatorMutate = vi.fn();
vi.mock('../use-orders', () => ({
  useDeliveryOrder: vi.fn(() => ({ data: undefined, isLoading: false })),
  useReleaseOrder: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useCancelOrder: vi.fn(() => ({ mutate: cancelMutate, isPending: false })),
  useClaimOrder: vi.fn(() => ({ mutate: claimMutate, isPending: false })),
  useReleaseOperator: vi.fn(() => ({ mutate: releaseOperatorMutate, isPending: false })),
}));

vi.mock('@/features/picking/use-pick-orders', () => ({
  useReleaseToPicking: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
}));

let mockRoles = ['order-read', 'order-write'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

// Current caller identity -- claim-ownership tests below key off this.
vi.mock('@/components/auth/auth-provider', () => ({
  useAuth: vi.fn(() => ({ userName: 'op-1' })),
}));

const mockUseOrderActivity = vi.fn();
vi.mock('@/features/insights/use-journals', async () => {
  const actual = await vi.importActual<typeof import('@/features/insights/use-journals')>(
    '@/features/insights/use-journals',
  );
  return {
    ...actual,
    useOrderActivity: (...args: unknown[]) => mockUseOrderActivity(...args),
  };
});

import { OrderDetail } from '../order-detail';
import { ORDER_STATE } from '@/types/orders';

function line(over: Partial<DeliveryOrderLineResponse>): DeliveryOrderLineResponse {
  return {
    id: 1,
    lineNumber: 1,
    itemDataId: 5,
    itemDataNumber: 'SKU-1',
    itemDataName: 'Widget',
    amount: 10,
    reservedAmount: 0,
    shortage: 0,
    state: 50,
    stateName: 'CREATED',
    lotNumber: null,
    unitPrice: null,
    externalNumber: null,
    pickedAmount: 0,
    substitutedAmount: 0,
    ...over,
  };
}

function order(over: Partial<DeliveryOrderResponse>): DeliveryOrderResponse {
  const id = over.id ?? 1;
  return {
    id,
    orderNumber: 'DO-1001',
    externalNumber: null,
    customerName: 'Acme Corp',
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
    state: 50,
    stateName: 'CREATED',
    orderStrategyId: null,
    clientId: 1,
    lines: [],
    created: '2026-06-12T08:00:00Z',
    modified: '2026-06-12T08:00:00Z',
    carrierName: null,
    carrierService: null,
    trackingNumber: null,
    shippedAt: null,
    destinationLocationId: null,
    destinationLocationName: null,
    operatorId: null,
    senderName: null,
    documentUrl: `/api/v1/delivery-orders/${id}/delivery-note.pdf`,
    labelUrl: null,
    ...over,
  };
}

function renderDetail(summary: DeliveryOrderResponse, canWrite = true) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OrderDetail summary={summary} canWrite={canWrite} />
    </QueryClientProvider>,
  );
}

describe('OrderDetail — B7 order value tile', () => {
  beforeEach(() => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: false });
  });

  it('shows Σ(amount × unitPrice) formatted as currency', () => {
    const o = order({
      lines: [
        line({ id: 1, amount: 10, unitPrice: 24.99 }),
        line({ id: 2, amount: 2, unitPrice: 5 }),
      ],
    });
    renderDetail(o);
    // 10*24.99 + 2*5 = 259.9
    expect(screen.getByText('$259.90')).toBeInTheDocument();
  });

  it('stays an honest "—" when every line is un-priced', () => {
    const o = order({
      lines: [line({ id: 1, amount: 10, unitPrice: null }), line({ id: 2, amount: 5, unitPrice: null })],
    });
    renderDetail(o);
    const tiles = screen.getAllByText('—');
    expect(tiles.length).toBeGreaterThan(0);
    expect(screen.queryByText('$0.00')).not.toBeInTheDocument();
  });

  it('treats a partially-priced order as the sum with unpriced lines contributing 0', () => {
    const o = order({
      lines: [line({ id: 1, amount: 10, unitPrice: 3 }), line({ id: 2, amount: 100, unitPrice: null })],
    });
    renderDetail(o);
    expect(screen.getByText('$30.00')).toBeInTheDocument();
  });
});

describe('OrderDetail — B8a activity feed', () => {
  it('requests journals filtered by this order via useOrderActivity(orderNumber)', () => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: false });
    renderDetail(order({ orderNumber: 'DO-2002' }));
    expect(mockUseOrderActivity).toHaveBeenCalledWith('DO-2002');
  });

  it('shows a loading line while the feed is loading', () => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: true });
    renderDetail(order({}));
    expect(screen.getByTestId('order-activity-loading')).toBeInTheDocument();
  });

  it('shows an honest empty state when the feed has no rows', () => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: false });
    renderDetail(order({}));
    expect(screen.getByTestId('order-activity-empty')).toBeInTheDocument();
    expect(screen.getByText('No recorded activity yet.')).toBeInTheDocument();
  });

  it('renders real journal rows when present', () => {
    const rows: JournalEntry[] = [
      {
        recordType: 3,
        recordTypeName: 'PICKED',
        productNumber: 'SKU-1',
        amount: 4,
        fromStorageLocation: 'A-01',
        toStorageLocation: null,
        lotNumber: null,
        correlationId: 'DO-1001',
        created: '2026-06-12T09:00:00Z',
      },
    ];
    mockUseOrderActivity.mockReturnValue({ data: rows, isLoading: false });
    renderDetail(order({}));
    expect(screen.queryByTestId('order-activity-empty')).not.toBeInTheDocument();
    expect(screen.getByText(/Pick 4/)).toBeInTheDocument();
  });
});

describe('OrderDetail — B10-1/7 real pick progress + line externalNumber', () => {
  beforeEach(() => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: false });
  });

  it('renders real pick progress from pickedAmount, not reservations', () => {
    const o = order({
      lines: [line({ amount: 10, reservedAmount: 10, pickedAmount: 4, substitutedAmount: 0 })],
    });
    renderDetail(o);
    const row = screen.getByTestId('order-line-row-1');
    expect(within(row).getByText('4 / 10')).toBeInTheDocument(); // qty cell — real done, not reservedAmount
    expect(within(row).getByText('40%')).toBeInTheDocument(); // real readout replaces the old hardcoded "—"
    expect(within(row).getByText('Picking')).toBeInTheDocument(); // in-progress status (scoped: the pipeline also has a "Picking" stage label)
  });

  it('marks a line Picked only when pickedAmount covers the ordered amount', () => {
    const o = order({
      lines: [line({ amount: 5, reservedAmount: 5, pickedAmount: 5, substitutedAmount: 0 })],
    });
    renderDetail(o);
    expect(screen.getByText('Picked')).toBeInTheDocument();
  });

  it('does not mark a line Picked when reserved but not yet picked (kills the old reservedAmount proxy)', () => {
    const o = order({
      lines: [line({ amount: 5, reservedAmount: 5, pickedAmount: 0, substitutedAmount: 0 })],
    });
    renderDetail(o);
    expect(screen.queryByText('Picked')).not.toBeInTheDocument();
    expect(screen.getByText('Pending')).toBeInTheDocument();
  });

  it('calls out substitution and shows the line external number', () => {
    const o = order({
      lines: [
        line({
          amount: 10,
          pickedAmount: 3,
          substitutedAmount: 2,
          externalNumber: 'CUST-LN-9',
        }),
      ],
    });
    renderDetail(o);
    const row = screen.getByTestId('order-line-row-1');
    expect(within(row).getByText(/2 sub/)).toBeInTheDocument();
    expect(screen.getByText('CUST-LN-9')).toBeInTheDocument();
    // Assert combined progress: pickedAmount (3) + substitutedAmount (2) = 5 / 10
    expect(within(row).getByText('5 / 10')).toBeInTheDocument();
    expect(within(row).getByText('50%')).toBeInTheDocument();
  });

  it('keeps reservedAmount visible as secondary info, not as the progress readout', () => {
    const o = order({
      lines: [line({ amount: 10, reservedAmount: 7, pickedAmount: 0, substitutedAmount: 0 })],
    });
    renderDetail(o);
    expect(screen.getByText(/res\s*7/)).toBeInTheDocument();
    // the readout is the real (zero) pick progress, not a reservation-derived percent
    expect(screen.getByText('0%')).toBeInTheDocument();
  });
});

describe('OrderDetail — B8b pipeline timestamps', () => {
  beforeEach(() => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: false });
  });

  it('labels Placed with order.created and honest-gaps intermediate stages', () => {
    const o = order({ created: '2026-06-12T08:00:00Z', state: 50 });
    renderDetail(o);
    const expectedPlaced = new Date(o.created).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
    expect(screen.getByText(expectedPlaced)).toBeInTheDocument();
  });

  it('labels Shipped with order.shippedAt when the order has shipped', () => {
    const o = order({ state: 680, shippedAt: '2026-06-13T10:00:00Z' });
    renderDetail(o);
    const expectedShipped = new Date(o.shippedAt as string).toLocaleString(undefined, {
      month: 'short',
      day: 'numeric',
      hour: '2-digit',
      minute: '2-digit',
    });
    expect(screen.getByText(expectedShipped)).toBeInTheDocument();
  });

  it('shows an honest "—" for Shipped when shippedAt is null', () => {
    const o = order({ state: 50, shippedAt: null });
    renderDetail(o);
    const dashes = screen.getAllByText('—');
    expect(dashes.length).toBeGreaterThan(0);
  });
});

describe('OrderDetail — Task 5 Cancel affordance', () => {
  const baseOrder = order({});

  beforeEach(() => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: false });
    cancelMutate.mockClear();
  });

  it('Cancel is offered pre-picking and hidden from PICKED(600) onward', () => {
    // order in RESERVED(400): button present
    const r1 = renderDetail({ ...baseOrder, state: 400 });
    expect(screen.getByTestId('order-cancel-button')).toBeInTheDocument();
    r1.unmount(); // each render() call adds a fresh container; unmount so
    // the next assertion can't see a stale button left over from this one
    // order in PICKED(600): backend 409s -> button must be absent
    const r2 = renderDetail({ ...baseOrder, state: 600 });
    expect(screen.queryByTestId('order-cancel-button')).not.toBeInTheDocument();
    r2.unmount();
    // CANCELED(800): absent
    renderDetail({ ...baseOrder, state: 800 });
    expect(screen.queryByTestId('order-cancel-button')).not.toBeInTheDocument();
  });

  it('is hidden entirely when the caller lacks order-write, regardless of state', () => {
    renderDetail({ ...baseOrder, state: 400 }, false);
    expect(screen.queryByTestId('order-cancel-button')).not.toBeInTheDocument();
  });

  it('confirm dialog drives the cancel mutation; dialog-cancel does not', async () => {
    const user = userEvent.setup();
    renderDetail({ ...baseOrder, state: 400 });
    await user.click(screen.getByTestId('order-cancel-button'));
    await user.click(screen.getByRole('button', { name: /keep order/i }));
    expect(cancelMutate).not.toHaveBeenCalled(); // mutation-verify the negative
    await user.click(screen.getByTestId('order-cancel-button'));
    await user.click(screen.getByRole('button', { name: /cancel order/i }));
    expect(cancelMutate).toHaveBeenCalledWith(baseOrder.id, expect.anything());
  });
});

describe('OrderDetail — Task 8 Delivery note document button', () => {
  beforeEach(() => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: false });
    viewPdf.mockClear();
  });

  it('is hidden below PICKED(600)', () => {
    renderDetail(order({ id: 7, state: ORDER_STATE.PENDING }));
    expect(screen.queryByTestId('doc-delivery-note-btn')).not.toBeInTheDocument();
  });

  it('is visible at PICKED(600) and above, even without write permission (read-only)', () => {
    renderDetail(order({ id: 7, state: ORDER_STATE.PICKED }), false);
    expect(screen.getByTestId('doc-delivery-note-btn')).toBeInTheDocument();

    renderDetail(order({ id: 7, state: 680 }));
    expect(screen.getAllByTestId('doc-delivery-note-btn').length).toBeGreaterThan(0);
  });

  it('requests the delivery-note PDF for this order id when clicked', async () => {
    const user = userEvent.setup();
    renderDetail(order({ id: 42, state: ORDER_STATE.PICKED }));
    await user.click(screen.getByTestId('doc-delivery-note-btn'));
    expect(viewPdf).toHaveBeenCalledWith('/api/v1/delivery-orders/42/delivery-note.pdf');
  });
});

describe('OrderDetail - Row 10 operator claim and derived document links', () => {
  beforeEach(() => {
    mockUseOrderActivity.mockReturnValue({ data: [], isLoading: false });
    claimMutate.mockClear();
    releaseOperatorMutate.mockClear();
    saveZpl.mockClear();
    mockRoles = ['order-read', 'order-write'];
  });

  it('offers Claim when unclaimed and calls the claim mutation with the order id', async () => {
    const user = userEvent.setup();
    renderDetail(order({ id: 9, operatorId: null }));
    await user.click(screen.getByTestId('order-claim-button'));
    expect(claimMutate).toHaveBeenCalledWith(9);
  });

  it('hides Claim entirely without order-write', () => {
    renderDetail(order({ operatorId: null }), false);
    expect(screen.queryByTestId('order-claim-button')).not.toBeInTheDocument();
  });

  it('offers Release claim to the claim holder and calls releaseOperator', async () => {
    const user = userEvent.setup();
    renderDetail(order({ id: 11, operatorId: 'op-1' }));
    expect(screen.queryByTestId('order-claim-button')).not.toBeInTheDocument();
    await user.click(screen.getByTestId('order-release-operator-button'));
    expect(releaseOperatorMutate).toHaveBeenCalledWith(11);
  });

  it('shows the holder name but no release button to a non-manager viewing someone else\'s claim', () => {
    renderDetail(order({ operatorId: 'someone-else' }));
    expect(screen.getByText('someone-else')).toBeInTheDocument();
    expect(screen.queryByTestId('order-release-operator-button')).not.toBeInTheDocument();
  });

  it("lets a MANAGER force-release another operator's claim", async () => {
    mockRoles = ['order-read', 'order-write', 'MANAGER'];
    const user = userEvent.setup();
    renderDetail(order({ id: 13, operatorId: 'someone-else' }));
    await user.click(screen.getByTestId('order-release-operator-button'));
    expect(releaseOperatorMutate).toHaveBeenCalledWith(13);
  });

  it('shows a read-only stream-status block when any of the four order streaming fields is set', () => {
    renderDetail(
      order({
        releaseModeOverride: 'STREAM',
        streamFirstAttemptAt: '2026-08-24T09:00:00Z',
        streamEscalatedAt: '2026-08-24T09:10:00Z',
        streamStalledAt: null,
      }),
    );
    const block = screen.getByTestId('order-stream-status');
    expect(within(block).getByText('STREAM')).toBeInTheDocument();
  });

  it('omits the stream-status block when none of the four fields is set', () => {
    renderDetail(
      order({
        releaseModeOverride: null,
        streamFirstAttemptAt: null,
        streamEscalatedAt: null,
        streamStalledAt: null,
      }),
    );
    expect(screen.queryByTestId('order-stream-status')).not.toBeInTheDocument();
  });

  it('shows the stream-status block from a single set timestamp alone (no override)', () => {
    renderDetail(
      order({
        releaseModeOverride: null,
        streamFirstAttemptAt: '2026-08-24T09:00:00Z',
        streamEscalatedAt: null,
        streamStalledAt: null,
      }),
    );
    expect(screen.getByTestId('order-stream-status')).toBeInTheDocument();
  });

  it('shows the Label affordance only once labelUrl is present, and downloads it as ZPL', async () => {
    const user = userEvent.setup();
    const r1 = renderDetail(order({ id: 42, labelUrl: null }));
    expect(screen.queryByTestId('doc-label-btn')).not.toBeInTheDocument();
    r1.unmount();

    renderDetail(
      order({ id: 42, orderNumber: 'DO-9', labelUrl: '/api/v1/shipping-units/7/label.zpl' }),
    );
    await user.click(screen.getByTestId('doc-label-btn'));
    expect(saveZpl).toHaveBeenCalledWith('/api/v1/shipping-units/7/label.zpl', 'DO-9-label.zpl');
  });
});
