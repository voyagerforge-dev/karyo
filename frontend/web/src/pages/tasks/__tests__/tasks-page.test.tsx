import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { TransportOrderResponse } from '@/types/tasks';
import type { WorkItemResponse } from '@/types/work';

// LocationPicker (rendered lazily inside the transport pane's complete dialog
// and the manual-move form) fetches /api/v1/locations; keep it a harmless empty page.
vi.mock('@/lib/api-client', () => ({
  api: {
    get: vi.fn(() =>
      Promise.resolve({
        content: [],
        page: { number: 0, size: 200, totalElements: 0, totalPages: 0 },
      }),
    ),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

const claimMutate = vi.fn();
const releaseMutate = vi.fn();

vi.mock('@/features/work/use-work', () => ({
  useAvailableWork: vi.fn(),
  useMyWork: vi.fn(),
  useClaimWork: vi.fn(() => ({ mutate: claimMutate, isPending: false })),
  useReleaseWork: vi.fn(() => ({ mutate: releaseMutate, isPending: false })),
}));

const transportOrderFixture: TransportOrderResponse = {
  id: 12,
  orderNumber: 'TO-1002',
  transportType: 'MOVE',
  unitLoadId: 11,
  unitLoadLabel: 'UL-BBB',
  sourceLocationId: 5,
  sourceLocationName: 'STR-01',
  destinationLocationId: 6,
  destinationLocationName: 'STR-02',
  suggestedLocationId: null,
  suggestedLocationName: null,
  state: 100,
  stateName: 'RELEASED',
  prio: 50,
  operatorId: null,
  executorType: 'HUMAN',
  note: null,
  goodsReceiptLineId: null,
  clientId: 1,
  created: '2026-06-13T09:00:00Z',
  modified: '2026-06-13T09:00:00Z',
  pausedAt: null,
  started: null,
  finished: null,
  successorId: null,
  externalNumber: null,
  externalId: null,
  itemDataId: null,
  itemDataNumber: null,
  lotNumber: null,
  amount: null,
  confirmedAmount: null,
  sourceStockUnitId: null,
};

vi.mock('../use-tasks', () => ({
  useTransportOrder: vi.fn(() => ({ data: transportOrderFixture, isLoading: false })),
  useTransportOrders: vi.fn(() => ({ data: undefined, isLoading: false })),
  useCreateManualMove: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useAssignTask: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useStartTask: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useCompleteTask: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useCancelTask: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  usePauseTask: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useResumeTask: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
}));

vi.mock('@/features/picking/use-pick-orders', () => ({
  usePickOrder: vi.fn(() => ({ data: undefined, isLoading: false })),
  useConfirmPick: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
}));

vi.mock('@/pages/orders/use-orders', () => ({
  useDeliveryOrder: vi.fn(() => ({ data: undefined, isLoading: false })),
}));

const replenishmentNeedsFixture = [
  {
    fixAssignmentId: 1,
    locationId: 1,
    locationName: 'A-01-01',
    itemDataId: 1,
    itemDataNumber: 'SKU-1',
    currentAmount: 2,
    minAmount: 10,
    desiredAmount: 20,
    belowMin: true,
    hasOpenTask: false,
  },
];

const scanReplenishMutate = vi.fn();
vi.mock('@/features/replenishment/use-replenishment', () => ({
  useReplenishmentNeeds: vi.fn(() => ({ data: replenishmentNeedsFixture, isLoading: false })),
  useScanReplenishment: vi.fn(() => ({ mutate: scanReplenishMutate, isPending: false })),
}));

let mockRoles = ['task-read', 'task-write', 'inventory-read'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

vi.mock('@/components/auth/auth-provider', () => ({
  useAuth: vi.fn(() => ({ userName: 'op-alice' })),
}));

// Fixtures: a MOVE item OPEN, a PICK item CLAIMED by 'op-bob', a COUNT item.
const moveItem: WorkItemResponse = {
  ref: 'MOVE:12',
  workType: 'MOVE',
  priority: 50,
  state: 'OPEN',
  claimedBy: null,
  zone: null,
  primaryLocation: 'STR-01',
  destination: 'STR-02',
  summary: 'Move UL-BBB',
  createdAt: '2026-06-13T09:00:00Z',
};

const pickItem: WorkItemResponse = {
  ref: 'PICK:7',
  workType: 'PICK',
  priority: 20,
  state: 'CLAIMED',
  claimedBy: 'op-bob',
  zone: 'A',
  primaryLocation: 'PICK-01',
  destination: null,
  summary: 'Pick order PO-500',
  createdAt: '2026-06-13T09:05:00Z',
};

// PT15: TRANSFER is a chain-continuation successor of a PUTAWAY/MOVE/REPLENISH
// hop -- it must route to the TransportPane like its parent types.
const transferItem: WorkItemResponse = {
  ref: 'TRANSFER:12',
  workType: 'TRANSFER',
  priority: 50,
  state: 'OPEN',
  claimedBy: null,
  zone: null,
  primaryLocation: 'STR-02',
  destination: 'STR-03',
  summary: 'Transfer UL-BBB',
  createdAt: '2026-06-13T09:15:00Z',
};

const countItem: WorkItemResponse = {
  ref: 'COUNT:3',
  workType: 'COUNT',
  priority: 80,
  state: 'OPEN',
  claimedBy: null,
  zone: null,
  primaryLocation: 'CC-01',
  destination: null,
  summary: 'Cycle count CC-01',
  createdAt: '2026-06-13T09:10:00Z',
};

// Cross-docking sprint (Task 3 fix-round): CROSS_DOCK now joins the work-inbox
// (TransportWorkProvider.workTypes()) -- it must route to the TransportPane like its
// PUTAWAY/MOVE/REPLENISH/TRANSFER siblings, same as the PT15 TRANSFER fixture above.
const crossDockItem: WorkItemResponse = {
  ref: 'CROSS_DOCK:13',
  workType: 'CROSS_DOCK',
  priority: 50,
  state: 'OPEN',
  claimedBy: null,
  zone: null,
  primaryLocation: 'DOCK-1',
  destination: 'XD-STAGE-01',
  summary: 'Cross-dock UL-CCC',
  createdAt: '2026-06-13T09:20:00Z',
};

const allItems = [moveItem, pickItem, countItem, transferItem, crossDockItem];

import { useAvailableWork, useMyWork } from '@/features/work/use-work';
import { useTransportOrders } from '../use-tasks';
import { TasksPage } from '../tasks-page';

// Row 22: a paused RESERVED putaway -- excluded from both the available and
// claimed-by-me work-inbox queries above, only reachable via the "Paused" chip.
const pausedOrderFixture: TransportOrderResponse = {
  ...transportOrderFixture,
  id: 99,
  orderNumber: 'TO-2099',
  transportType: 'PUTAWAY',
  unitLoadLabel: 'UL-PAUSED',
  state: 400,
  stateName: 'RESERVED',
  operatorId: 'op-alice',
  pausedAt: '2026-08-14T00:00:00Z',
};

function renderPage(initialEntries: string[] = ['/tasks']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <TasksPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

/**
 * Renders TasksPage alongside navigation buttons that push a new URL onto the
 * SAME router history -- TasksPage stays mounted the whole time, unlike
 * renderPage() rerenders. This is what a command-palette navigation to
 * /tasks?type=... or /tasks?create=1 while already on /tasks looks like (I1,
 * final review): the useState initializers never re-run on a mounted page,
 * only the searchParams effect fires.
 */
function renderPageWithNav(initialEntries: string[] = ['/tasks']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  function Harness() {
    const navigate = useNavigate();
    return (
      <>
        <button data-testid="nav-type-pick" onClick={() => navigate('/tasks?type=PICK')}>
          nav
        </button>
        <button data-testid="nav-create" onClick={() => navigate('/tasks?create=1')}>
          nav
        </button>
        <TasksPage />
      </>
    );
  }
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <Harness />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  claimMutate.mockClear();
  releaseMutate.mockClear();
  scanReplenishMutate.mockClear();
  vi.mocked(useAvailableWork).mockReturnValue({
    data: allItems,
    isLoading: false,
  } as ReturnType<typeof useAvailableWork>);
  vi.mocked(useMyWork).mockReturnValue({
    data: [] as WorkItemResponse[],
    isLoading: false,
  } as ReturnType<typeof useMyWork>);
  vi.mocked(useTransportOrders).mockReturnValue({
    data: undefined,
    isLoading: false,
  } as ReturnType<typeof useTransportOrders>);
});

afterEach(() => {
  mockRoles = ['task-read', 'task-write', 'inventory-read'];
});

describe('TasksPage', () => {
  it('lists all work types with type chips and claim state', () => {
    renderPage();

    expect(screen.getByTestId('work-row-MOVE:12')).toBeInTheDocument();
    expect(screen.getByTestId('work-row-PICK:7')).toBeInTheDocument();
    expect(screen.getByTestId('work-row-COUNT:3')).toBeInTheDocument();

    expect(screen.getByTestId('tasks-page')).toBeInTheDocument();

    const pickRow = screen.getByTestId('work-row-PICK:7');
    expect(within(pickRow).getByText('op-bob', { exact: false })).toBeInTheDocument();

    // Type chips (StatusPill labels on each row + the master-list filter chips)
    expect(within(screen.getByTestId('work-row-MOVE:12')).getByText('Move')).toBeInTheDocument();
    expect(within(screen.getByTestId('work-row-PICK:7')).getByText('Pick')).toBeInTheDocument();
    expect(within(screen.getByTestId('work-row-COUNT:3')).getByText('Count')).toBeInTheDocument();
  });

  // The end-user guide (docs/user-guide/desktop-console.md) tells a supervisor that
  // Tasks is one of the screens that waits for a click, so an empty right-hand pane
  // means "nothing selected yet", not "the list failed to load".
  it('starts with the empty "Select a work item" pane, with rows already listed', () => {
    renderPage();

    expect(screen.getByText('Select a work item')).toBeInTheDocument();
    expect(screen.getByTestId('work-row-PICK:7')).toBeInTheDocument();
  });

  it('?type=PICK deep link preselects the chip and filters', () => {
    renderPage(['/tasks?type=PICK']);

    expect(screen.getByTestId('work-row-PICK:7')).toBeInTheDocument();
    expect(screen.queryByTestId('work-row-MOVE:12')).not.toBeInTheDocument();
    expect(screen.queryByTestId('work-row-COUNT:3')).not.toBeInTheDocument();
  });

  // I1 (final review): the old bug read ?type=/?create= only in useState
  // initializers, so a command-palette navigation while /tasks was already
  // mounted silently no-opped ("Create move" regressed vs the old page).
  it('applies a ?type= param pushed while the page stays mounted', async () => {
    const user = userEvent.setup();
    renderPageWithNav();

    // Starts unfiltered -- all three fixtures visible.
    expect(screen.getByTestId('work-row-MOVE:12')).toBeInTheDocument();
    expect(screen.getByTestId('work-row-PICK:7')).toBeInTheDocument();

    await user.click(screen.getByTestId('nav-type-pick'));

    expect(await screen.findByTestId('work-row-PICK:7')).toBeInTheDocument();
    expect(screen.queryByTestId('work-row-MOVE:12')).not.toBeInTheDocument();
    expect(screen.queryByTestId('work-row-COUNT:3')).not.toBeInTheDocument();
  });

  it('applies a ?create=1 param pushed while the page stays mounted', async () => {
    const user = userEvent.setup();
    renderPageWithNav();

    expect(screen.queryByTestId('manual-move-form')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('nav-create'));

    expect(await screen.findByTestId('manual-move-form')).toBeInTheDocument();
  });

  it('selecting a transport item renders the transport pane', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('work-row-MOVE:12'));
    expect(await screen.findByTestId('task-detail-state')).toBeInTheDocument();
  });

  // PT15: TRANSFER items must route to the TransportPane like PUTAWAY/MOVE/
  // REPLENISH -- previously missing from the isTransport check.
  it('selecting a TRANSFER item renders the transport pane too', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('work-row-TRANSFER:12'));
    expect(await screen.findByTestId('task-detail-state')).toBeInTheDocument();
  });

  // Cross-docking sprint (Task 3 fix-round): CROSS_DOCK items must route to the
  // TransportPane like PUTAWAY/MOVE/REPLENISH/TRANSFER -- same regression shape as the
  // PT15 TRANSFER pin above (isTransport must list every transport-order type).
  it('selecting a CROSS_DOCK item renders the transport pane too', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('work-row-CROSS_DOCK:13'));
    expect(await screen.findByTestId('task-detail-state')).toBeInTheDocument();
  });

  it('claim button claims an OPEN item via the work API', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('work-row-MOVE:12'));
    await user.click(await screen.findByTestId('work-claim-button'));

    expect(claimMutate).toHaveBeenCalledWith('MOVE:12');
  });

  it('New move opens the manual-move form for task-write holders', async () => {
    const user = userEvent.setup();
    const first = renderPage();

    await user.click(screen.getByTestId('new-move-button'));
    expect(await screen.findByTestId('manual-move-form')).toBeInTheDocument();

    first.unmount();
    mockRoles = ['task-read', 'inventory-read'];
    renderPage();
    expect(screen.queryByTestId('new-move-button')).not.toBeInTheDocument();
  });

  it('REPLENISH chip reveals the needs section with scan action', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Replenish' }));

    expect(await screen.findByText('A-01-01')).toBeInTheDocument();
    const scanButton = screen.getByTestId('scan-replenishment-button');
    expect(scanButton).toBeInTheDocument();

    await user.click(scanButton);
    expect(scanReplenishMutate).toHaveBeenCalled();
  });

  // Row 22: the "Paused" chip switches the list to the directly-fetched
  // paused-only query -- the merged work-inbox list never carries these rows.
  it('Paused chip shows only the paused transport orders, not the work-inbox items', async () => {
    vi.mocked(useTransportOrders).mockReturnValue({
      data: {
        content: [pausedOrderFixture],
        page: { number: 0, size: 50, totalElements: 1, totalPages: 1 },
      },
      isLoading: false,
    } as ReturnType<typeof useTransportOrders>);
    const user = userEvent.setup();
    renderPage();

    expect(screen.getByTestId('work-row-MOVE:12')).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: 'Paused' }));

    expect(screen.getByTestId('work-row-PUTAWAY:99')).toBeInTheDocument();
    expect(screen.queryByTestId('work-row-MOVE:12')).not.toBeInTheDocument();
    expect(vi.mocked(useTransportOrders)).toHaveBeenCalledWith({ paused: true });
  });

  it('selecting a paused row opens the transport pane', async () => {
    vi.mocked(useTransportOrders).mockReturnValue({
      data: {
        content: [pausedOrderFixture],
        page: { number: 0, size: 50, totalElements: 1, totalPages: 1 },
      },
      isLoading: false,
    } as ReturnType<typeof useTransportOrders>);
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Paused' }));
    await user.click(screen.getByTestId('work-row-PUTAWAY:99'));

    expect(await screen.findByTestId('task-detail-state')).toBeInTheDocument();
  });
});
