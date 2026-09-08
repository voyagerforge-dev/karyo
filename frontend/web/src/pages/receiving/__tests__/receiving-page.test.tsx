import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { PaginatedResponse } from '@/types/api';
import { GOODS_RECEIPT_TYPE, type GoodsReceiptResponse } from '@/types/receiving';

// Mock the API client so LocationPicker (edit dialog) never hits the network.
vi.mock('@/lib/api-client', () => ({
  api: {
    get: vi.fn(() =>
      Promise.resolve({
        content: [],
        page: { number: 0, size: 100, totalElements: 0, totalPages: 0 },
      }),
    ),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
}));

const navigateMock = vi.fn();
vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return { ...actual, useNavigate: () => navigateMock };
});

const claimMutate = vi.fn();
const releaseMutate = vi.fn();
const pauseMutate = vi.fn();
const resumeMutate = vi.fn();
const updateMutate = vi.fn();
const cancelMutate = vi.fn();
const createMutate = vi.fn();
const reverseLineMutate = vi.fn();

vi.mock('../use-receiving', () => ({
  useGoodsReceipts: vi.fn(),
  useGoodsReceipt: vi.fn(),
  useCreateGoodsReceipt: vi.fn(() => ({ mutate: createMutate, isPending: false })),
  useCancelReceipt: vi.fn(() => ({ mutate: cancelMutate, isPending: false })),
  useClaimReceipt: vi.fn(() => ({ mutate: claimMutate, isPending: false })),
  useReleaseReceipt: vi.fn(() => ({ mutate: releaseMutate, isPending: false })),
  usePauseReceipt: vi.fn(() => ({ mutate: pauseMutate, isPending: false })),
  useResumeReceipt: vi.fn(() => ({ mutate: resumeMutate, isPending: false })),
  useUpdateGoodsReceipt: vi.fn(() => ({ mutate: updateMutate, isPending: false })),
  useReverseLine: vi.fn(() => ({ mutate: reverseLineMutate, isPending: false })),
}));

let mockRoles = ['order-read', 'order-write'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

// Current caller identity -- receipt C below is claimed by this operator.
vi.mock('@/components/auth/auth-provider', () => ({
  useAuth: vi.fn(() => ({ userName: 'op-1' })),
}));

const receiptA: GoodsReceiptResponse = {
  id: 1,
  receiptNumber: 'GR-2001',
  asns: [],
  carrierName: 'FedEx',
  deliveryNoteNumber: null,
  notes: null,
  receiptType: GOODS_RECEIPT_TYPE.NORMAL,
  prio: 50,
  receiptDate: null,
  dockLocationId: null,
  dockLocationName: null,
  operatorId: null,
  pausedAt: null,
  state: 500,
  stateName: 'STARTED',
  clientId: 1,
  lines: [],
  created: '2026-07-15T08:00:00Z',
  modified: '2026-07-15T08:00:00Z',
};

const receiptB: GoodsReceiptResponse = {
  id: 2,
  receiptNumber: 'GR-2002',
  asns: [{ id: 9, asnNumber: 'ASN-500' }],
  carrierName: 'UPS',
  deliveryNoteNumber: null,
  notes: null,
  receiptType: GOODS_RECEIPT_TYPE.RETOUR,
  prio: 50,
  receiptDate: null,
  dockLocationId: null,
  dockLocationName: null,
  operatorId: null,
  pausedAt: null,
  state: 50,
  stateName: 'CREATED',
  clientId: 1,
  lines: [],
  created: '2026-07-15T09:00:00Z',
  modified: '2026-07-15T09:00:00Z',
};

// Paused + claimed by the current caller ('op-1') -- exercises the "release
// claim" (owner) branch and the B7 header fields together.
const receiptC: GoodsReceiptResponse = {
  id: 3,
  receiptNumber: 'GR-2003',
  asns: [],
  carrierName: null,
  deliveryNoteNumber: null,
  notes: null,
  receiptType: GOODS_RECEIPT_TYPE.NORMAL,
  prio: 10,
  receiptDate: '2026-07-21',
  dockLocationId: 40,
  dockLocationName: 'DOCK-3',
  operatorId: 'op-1',
  pausedAt: '2026-07-20T10:00:00Z',
  state: 500,
  stateName: 'STARTED',
  clientId: 1,
  lines: [
    {
      id: 31,
      asnLineId: null,
      itemDataId: 5,
      itemDataNumber: 'DEMO-MOUSE',
      amount: 12,
      locationId: 7,
      locationName: 'STR-01',
      unitLoadLabel: 'UL-1',
      stockUnitId: 1,
      unitLoadId: 1,
      lotNumber: null,
      bestBefore: null,
      serialNumber: null,
      packagingUnitId: null,
      lockType: 103,
      note: null,
      qaHold: true,
      reversed: false,
      reversedAt: null,
      storageStrategyId: null,
    },
  ],
  created: '2026-07-14T08:00:00Z',
  modified: '2026-07-20T10:00:00Z',
};

// Claimed by a different operator -- exercises the "claimed by other" /
// manager-release branch.
const receiptD: GoodsReceiptResponse = {
  id: 4,
  receiptNumber: 'GR-2004',
  asns: [],
  carrierName: null,
  deliveryNoteNumber: null,
  notes: null,
  receiptType: GOODS_RECEIPT_TYPE.NORMAL,
  prio: 50,
  receiptDate: null,
  dockLocationId: null,
  dockLocationName: null,
  operatorId: 'op-2',
  pausedAt: null,
  state: 500,
  stateName: 'STARTED',
  clientId: 1,
  lines: [],
  created: '2026-07-16T08:00:00Z',
  modified: '2026-07-16T08:00:00Z',
};

// Finished (700, closed) -- exercises I1 (Edit must not be offered) and the
// pause-banner co-render gate (M3).
const receiptE: GoodsReceiptResponse = {
  id: 5,
  receiptNumber: 'GR-2005',
  asns: [],
  carrierName: null,
  deliveryNoteNumber: null,
  notes: null,
  receiptType: GOODS_RECEIPT_TYPE.NORMAL,
  prio: 50,
  receiptDate: null,
  dockLocationId: null,
  dockLocationName: null,
  operatorId: null,
  pausedAt: null,
  state: 700,
  stateName: 'FINISHED',
  clientId: 1,
  lines: [],
  created: '2026-07-10T08:00:00Z',
  modified: '2026-07-10T09:00:00Z',
};

// Canceled (800) with zero lines and a stale pausedAt (backend never clears
// pausedAt on cancel -- see GoodsReceiptService.cancel) -- exercises I2 (Cancel
// must not be re-offered) and M3 (paused banner/Resume must not co-render).
const receiptF: GoodsReceiptResponse = {
  id: 6,
  receiptNumber: 'GR-2006',
  asns: [],
  carrierName: null,
  deliveryNoteNumber: null,
  notes: null,
  receiptType: GOODS_RECEIPT_TYPE.NORMAL,
  prio: 50,
  receiptDate: null,
  dockLocationId: null,
  dockLocationName: null,
  operatorId: null,
  pausedAt: '2026-07-11T10:00:00Z',
  state: 800,
  stateName: 'CANCELED',
  clientId: 1,
  lines: [],
  created: '2026-07-11T08:00:00Z',
  modified: '2026-07-11T10:30:00Z',
};

// STARTED(500), unpaused, with one unreversed line (41) and one already-
// reversed line (42) -- exercises the three-way reverse-button gate: open +
// unreversed -> button present (41); reversed -> button absent, "Reversed"
// pill instead (42).
const receiptG: GoodsReceiptResponse = {
  id: 7,
  receiptNumber: 'GR-2007',
  asns: [],
  carrierName: null,
  deliveryNoteNumber: null,
  notes: null,
  receiptType: GOODS_RECEIPT_TYPE.NORMAL,
  prio: 50,
  receiptDate: null,
  dockLocationId: null,
  dockLocationName: null,
  operatorId: null,
  pausedAt: null,
  state: 500,
  stateName: 'STARTED',
  clientId: 1,
  lines: [
    {
      id: 41,
      asnLineId: null,
      itemDataId: 5,
      itemDataNumber: 'DEMO-MOUSE',
      amount: 10,
      locationId: 7,
      locationName: 'STR-01',
      unitLoadLabel: 'UL-41',
      stockUnitId: 41,
      unitLoadId: 41,
      lotNumber: null,
      bestBefore: null,
      serialNumber: null,
      packagingUnitId: null,
      lockType: null,
      note: null,
      qaHold: false,
      reversed: false,
      reversedAt: null,
      storageStrategyId: null,
    },
    {
      id: 42,
      asnLineId: null,
      itemDataId: 5,
      itemDataNumber: 'DEMO-MOUSE',
      amount: 5,
      locationId: 7,
      locationName: 'STR-01',
      unitLoadLabel: 'UL-42',
      stockUnitId: 42,
      unitLoadId: 42,
      lotNumber: null,
      bestBefore: null,
      serialNumber: null,
      packagingUnitId: null,
      lockType: null,
      note: null,
      qaHold: false,
      reversed: true,
      reversedAt: '2026-07-21T09:00:00Z',
      storageStrategyId: null,
    },
  ],
  created: '2026-07-21T08:00:00Z',
  modified: '2026-07-21T09:00:00Z',
};

const mockReceipts = [receiptA, receiptB, receiptC, receiptD, receiptE, receiptF, receiptG];

const mockPaginatedResponse: PaginatedResponse<GoodsReceiptResponse> = {
  content: mockReceipts,
  page: { number: 0, size: 50, totalElements: mockReceipts.length, totalPages: 1 },
};

// Import after mocks
import { useGoodsReceipts, useGoodsReceipt } from '../use-receiving';
import { ReceivingPage } from '../receiving-page';

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <ReceivingPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  claimMutate.mockClear();
  releaseMutate.mockClear();
  pauseMutate.mockClear();
  resumeMutate.mockClear();
  updateMutate.mockClear();
  cancelMutate.mockClear();
  createMutate.mockClear();
  reverseLineMutate.mockClear();
  navigateMock.mockClear();
  vi.mocked(useGoodsReceipts).mockReturnValue({
    data: mockPaginatedResponse,
    isLoading: false,
    isError: false,
    error: null,
  } as ReturnType<typeof useGoodsReceipts>);
  vi.mocked(useGoodsReceipt).mockImplementation(
    (id?: number) =>
      ({
        data: mockReceipts.find((r) => r.id === id),
        isLoading: false,
      }) as ReturnType<typeof useGoodsReceipt>,
  );
});

afterEach(() => {
  mockRoles = ['order-read', 'order-write'];
});

describe('ReceivingPage', () => {
  it('lists receipts with type badge, paused indicator and status pill', () => {
    renderPage();

    const rowB = screen.getByTestId('receipt-row-2');
    expect(within(rowB).getByText('RETOUR')).toBeInTheDocument();

    const rowC = screen.getByTestId('receipt-row-3');
    expect(within(rowC).getByText('Paused')).toBeInTheDocument();

    const rowA = screen.getByTestId('receipt-row-1');
    expect(within(rowA).getByText('Receiving')).toBeInTheDocument();
  });

  it('detail pane shows claim state and B7 header fields', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('receipt-row-3'));

    expect(screen.getByText('op-1')).toBeInTheDocument();
    expect(screen.getByText('10')).toBeInTheDocument();
    expect(screen.getByText('DOCK-3')).toBeInTheDocument();
    // Already claimed (by the current caller) -- claim is not idempotent, so
    // Claim must not be offered again.
    expect(screen.queryByTestId('receipt-claim-button')).not.toBeInTheDocument();
  });

  it('claim/release/pause/resume buttons call their hooks per state', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('receipt-row-1'));

    expect(screen.getByTestId('receipt-claim-button')).toBeInTheDocument();
    expect(screen.getByTestId('receipt-pause-button')).toBeInTheDocument();
    expect(screen.queryByTestId('receipt-release-button')).not.toBeInTheDocument();
    expect(screen.queryByTestId('receipt-resume-button')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('receipt-claim-button'));
    expect(claimMutate).toHaveBeenCalledWith(1);

    await user.click(screen.getByTestId('receipt-pause-button'));
    expect(pauseMutate).toHaveBeenCalledWith(1);
  });

  it('release is offered to the claim owner and hidden otherwise unless manager', async () => {
    const user = userEvent.setup();
    const first = renderPage();

    await user.click(screen.getByTestId('receipt-row-4'));
    expect(screen.getByText('Claimed by op-2')).toBeInTheDocument();
    expect(screen.queryByTestId('receipt-release-button')).not.toBeInTheDocument();
    // Already claimed (by someone else) -- claim is not idempotent, so Claim
    // must not be offered.
    expect(screen.queryByTestId('receipt-claim-button')).not.toBeInTheDocument();

    first.unmount();
    mockRoles = ['order-read', 'order-write', 'MANAGER'];
    renderPage();
    await user.click(screen.getByTestId('receipt-row-4'));

    const releaseButton = screen.getByTestId('receipt-release-button');
    expect(releaseButton).toBeInTheDocument();
    expect(releaseButton).toHaveTextContent(/manager/i);

    await user.click(releaseButton);
    expect(releaseMutate).toHaveBeenCalledWith(4);
  });

  it('Cancel is offered only for zero-line receipts, never a STARTED receipt with lines', async () => {
    const user = userEvent.setup();
    renderPage();

    // receiptC: STARTED(500) with one line -- backend only cancels empty
    // receipts, so Cancel must not be offered here even though state is open.
    await user.click(screen.getByTestId('receipt-row-3'));
    expect(screen.queryByTestId('receipt-cancel-button')).not.toBeInTheDocument();

    // receiptB: CREATED(50) with zero lines -- Cancel must be offered.
    await user.click(screen.getByTestId('receipt-row-2'));
    expect(screen.getByTestId('receipt-cancel-button')).toBeInTheDocument();

    // receiptF: CANCELED(800) with zero lines -- zero-line check alone would
    // re-offer Cancel on an already-canceled receipt (409 on click); the
    // `status.open` half of the gate must suppress it (I2).
    await user.click(screen.getByTestId('receipt-row-6'));
    expect(screen.queryByTestId('receipt-cancel-button')).not.toBeInTheDocument();
  });

  it('Edit is not offered on a closed (Finished) receipt', async () => {
    const user = userEvent.setup();
    renderPage();

    // receiptE: FINISHED(700) -- backend PUT 409s 'order-not-editable' unless
    // CREATED/STARTED, so Edit must not render (I1).
    await user.click(screen.getByTestId('receipt-row-5'));
    expect(screen.queryByTestId('receipt-edit-button')).not.toBeInTheDocument();
  });

  it('paused banner and Resume do not render on a closed receipt with a stale pausedAt', async () => {
    const user = userEvent.setup();
    renderPage();

    // receiptF: CANCELED(800) but pausedAt is still set (cancel doesn't clear
    // it) -- the banner/Resume must be gated on `status.open`, not `pausedAt`
    // alone (M3).
    await user.click(screen.getByTestId('receipt-row-6'));
    expect(screen.queryByTestId('receipt-paused-banner')).not.toBeInTheDocument();
    expect(screen.queryByTestId('receipt-resume-button')).not.toBeInTheDocument();
  });

  it('edit dialog PUTs prio/receiptDate/dock via useUpdateGoodsReceipt', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('receipt-row-1'));
    await user.click(screen.getByTestId('receipt-edit-button'));

    const prioInput = screen.getByLabelText(/prio/i);
    await user.clear(prioInput);
    await user.type(prioInput, '10');

    await user.click(screen.getByTestId('receipt-edit-save'));

    expect(updateMutate).toHaveBeenCalledWith(
      expect.objectContaining({ id: 1, prio: 10 }),
      expect.anything(),
    );
  });

  it('Open workbench navigates to /receiving/{id}', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('receipt-row-1'));
    await user.click(screen.getByTestId('receipt-open-workbench'));

    expect(navigateMock).toHaveBeenCalledWith('/receiving/1');
  });

  describe('reverse line (B3)', () => {
    it('three-way gate: open+unreversed -> present, reversed -> absent, paused -> absent', async () => {
      const user = userEvent.setup();
      renderPage();

      // receiptG: STARTED(500), unpaused -- line 41 is unreversed (button
      // present), line 42 is already reversed (button absent, pill instead).
      await user.click(screen.getByTestId('receipt-row-7'));
      expect(screen.getByTestId('line-reverse-btn-41')).toBeInTheDocument();
      expect(screen.queryByTestId('line-reverse-btn-42')).not.toBeInTheDocument();
      expect(screen.getByTestId('line-reversed-42')).toBeInTheDocument();

      // receiptC: STARTED(500) but paused, with one unreversed line (31) --
      // the backend refuses reversal while paused, so the button must not be
      // offered even though the line itself is open/unreversed.
      await user.click(screen.getByTestId('receipt-row-3'));
      expect(screen.queryByTestId('line-reverse-btn-31')).not.toBeInTheDocument();
    });

    it('dialog cancel does NOT call the mutation; confirm calls it with the line id', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByTestId('receipt-row-7'));
      await user.click(screen.getByTestId('line-reverse-btn-41'));

      expect(screen.getByText('Reverse line?')).toBeInTheDocument();
      await user.click(screen.getByRole('button', { name: 'Keep line' }));
      expect(reverseLineMutate).not.toHaveBeenCalled();
      expect(screen.queryByText('Reverse line?')).not.toBeInTheDocument();

      await user.click(screen.getByTestId('line-reverse-btn-41'));
      const dialog = screen.getByRole('alertdialog');
      await user.click(within(dialog).getByRole('button', { name: 'Reverse line' }));
      expect(reverseLineMutate).toHaveBeenCalledWith(
        { id: 7, lineId: 41 },
        expect.anything(),
      );
    });
  });
});
