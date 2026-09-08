import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { TransportOrderResponse } from '@/types/tasks';
import type { WorkItemResponse } from '@/types/work';
import { api } from '@/lib/api-client';

// LocationPicker fetches /api/v1/locations; return the suggested + one alternate.
vi.mock('@/lib/api-client', () => ({
  api: {
    get: vi.fn(() =>
      Promise.resolve({
        content: [
          { id: 5, name: 'STR-01', area: { name: 'Storage A' } },
          { id: 6, name: 'STR-02', area: { name: 'Storage A' } },
        ],
        page: { number: 0, size: 200, totalElements: 2, totalPages: 1 },
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

const completeMutate = vi.fn();
const assignMutate = vi.fn();
const startMutate = vi.fn();
const releaseMutate = vi.fn();
const pauseMutate = vi.fn();
const resumeMutate = vi.fn();

const startedTask: TransportOrderResponse = {
  id: 2,
  orderNumber: 'TO-2002',
  transportType: 'PUTAWAY',
  unitLoadId: 11,
  unitLoadLabel: 'UL-CCC',
  sourceLocationId: 90,
  sourceLocationName: 'DOCK-1',
  destinationLocationId: null,
  destinationLocationName: null,
  suggestedLocationId: 5,
  suggestedLocationName: 'STR-01',
  state: 500,
  stateName: 'STARTED',
  prio: 50,
  operatorId: 'op-alice',
  executorType: 'HUMAN',
  note: null,
  goodsReceiptLineId: 7,
  clientId: 1,
  created: '2026-06-13T08:00:00Z',
  modified: '2026-06-13T08:30:00Z',
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

// RESERVED counterpart of startedTask -- the backend only allows Release from
// this state (see TaskService.release), so the affordance must show here and
// NOT for startedTask above.
const reservedTask: TransportOrderResponse = {
  ...startedTask,
  state: 400,
  stateName: 'RESERVED',
};

let currentTask: TransportOrderResponse = startedTask;

vi.mock('../use-tasks', () => ({
  useTransportOrder: vi.fn(() => ({ data: currentTask, isLoading: false })),
  useAssignTask: vi.fn(() => ({ mutate: assignMutate, isPending: false })),
  useStartTask: vi.fn(() => ({ mutate: startMutate, isPending: false })),
  useCompleteTask: vi.fn(() => ({ mutate: completeMutate, isPending: false })),
  useCancelTask: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  usePauseTask: vi.fn(() => ({ mutate: pauseMutate, isPending: false })),
  useResumeTask: vi.fn(() => ({ mutate: resumeMutate, isPending: false })),
}));

vi.mock('@/features/work/use-work', () => ({
  useReleaseWork: vi.fn(() => ({ mutate: releaseMutate, isPending: false })),
}));

import { TransportPane } from '../transport-pane';

// refSourceId('PUTAWAY:2') === 2 -- must line up with startedTask.id above; the
// mocked useTransportOrder ignores the id argument and always returns startedTask.
const workItem: WorkItemResponse = {
  ref: 'PUTAWAY:2',
  workType: 'PUTAWAY',
  priority: 50,
  state: 'CLAIMED',
  claimedBy: 'op-alice',
  zone: null,
  primaryLocation: 'DOCK-1',
  destination: null,
  summary: 'TO-2002',
  createdAt: '2026-06-13T08:00:00Z',
};

function renderPane() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <TransportPane workItem={workItem} canWrite currentOperatorId="op-alice" />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  completeMutate.mockReset();
  assignMutate.mockReset();
  startMutate.mockReset();
  releaseMutate.mockReset();
  pauseMutate.mockReset();
  resumeMutate.mockReset();
  currentTask = startedTask;
  // Restore the default (always-resolves) api.get behavior -- the merge-lookup-error
  // test below replaces it for the duration of that one test.
  vi.mocked(api.get).mockImplementation(() =>
    Promise.resolve({
      content: [
        { id: 5, name: 'STR-01', area: { name: 'Storage A' } },
        { id: 6, name: 'STR-02', area: { name: 'Storage A' } },
      ],
      page: { number: 0, size: 200, totalElements: 2, totalPages: 1 },
    }),
  );
});

describe('TransportPane', () => {
  it('shows the suggested destination and a Complete action for STARTED tasks', () => {
    renderPane();
    expect(screen.getByTestId('task-suggested-destination')).toHaveTextContent('STR-01');
    expect(screen.getByTestId('task-complete-button')).toBeInTheDocument();
  });

  it('completes accepting the suggestion (sends {} — no destination override)', async () => {
    const user = userEvent.setup();
    renderPane();

    await user.click(screen.getByTestId('task-complete-button'));
    // The complete dialog opens with the location prefilled to the suggestion (STR-01).
    const dialog = await screen.findByTestId('task-complete-dialog');
    expect(dialog).toHaveTextContent('STR-01');

    await user.click(screen.getByTestId('task-complete-confirm'));

    // Accepting the suggestion sends only the id (no destination override).
    expect(completeMutate).toHaveBeenCalledTimes(1);
    expect(completeMutate.mock.calls[0][0]).toEqual({ id: 2 });
  });

  it('completes with an override when a different location is picked', async () => {
    const user = userEvent.setup();
    renderPane();

    await user.click(screen.getByTestId('task-complete-button'));
    await screen.findByTestId('task-complete-dialog');

    // Clear the prefilled suggestion, then pick STR-02 as an override.
    await user.click(screen.getByRole('button', { name: /clear location/i }));
    const input = screen.getByPlaceholderText(/search location/i);
    await user.type(input, 'STR-02');
    await user.click(await screen.findByRole('option', { name: /STR-02/i }));

    await user.click(screen.getByTestId('task-complete-confirm'));

    expect(completeMutate.mock.calls[0][0]).toMatchObject({
      id: 2,
      destinationLocationId: 6,
      destinationLocationName: 'STR-02',
    });
  });

  // I4 (final-review): the backend only allows releasing a RESERVED transport
  // order (TaskService.release) -- offering Release once STARTED always 409s.
  it('does not offer Release for a STARTED transport order', () => {
    currentTask = startedTask;
    renderPane();
    expect(screen.queryByTestId('work-release-button')).not.toBeInTheDocument();
  });

  it('offers Release for a RESERVED transport order claimed by the current operator', async () => {
    const user = userEvent.setup();
    currentTask = reservedTask;
    renderPane();

    const releaseButton = screen.getByTestId('work-release-button');
    expect(releaseButton).toBeInTheDocument();

    await user.click(releaseButton);
    expect(releaseMutate).toHaveBeenCalledWith('PUTAWAY:2');
  });

  // PT18: pause/resume are orthogonal to `state` — offered for any non-terminal
  // order, mirroring the receipt-detail.tsx precedent.
  it('offers Pause for a non-terminal transport order and calls pause on click', async () => {
    const user = userEvent.setup();
    currentTask = startedTask;
    renderPane();

    expect(screen.queryByTestId('task-resume-button')).not.toBeInTheDocument();
    await user.click(screen.getByTestId('task-pause-button'));
    expect(pauseMutate).toHaveBeenCalledWith(2);
  });

  it('shows the paused banner and Resume, disabling assign/start/complete', async () => {
    const user = userEvent.setup();
    currentTask = { ...startedTask, pausedAt: '2026-08-01T10:00:00Z' };
    renderPane();

    expect(screen.getByTestId('task-paused-banner')).toHaveTextContent('Paused since');
    expect(screen.queryByTestId('task-pause-button')).not.toBeInTheDocument();
    expect(screen.getByTestId('task-complete-button')).toBeDisabled();
    // Cancel is deliberately NOT guarded against pause (TaskService.cancel).
    expect(screen.getByTestId('task-cancel-button')).not.toBeDisabled();

    await user.click(screen.getByTestId('task-resume-button'));
    expect(resumeMutate).toHaveBeenCalledWith(2);
  });

  it('disables Assign to me while a RELEASED order is paused', () => {
    currentTask = { ...startedTask, state: 100, stateName: 'RELEASED', pausedAt: '2026-08-01T10:00:00Z' };
    renderPane();
    expect(screen.getByTestId('task-assign-button')).toBeDisabled();
  });

  it('disables Start while a RESERVED order is paused', () => {
    currentTask = { ...reservedTask, pausedAt: '2026-08-01T10:00:00Z' };
    renderPane();
    expect(screen.getByTestId('task-start-button')).toBeDisabled();
  });

  // PT17: the partial-qty input only appears when the order carries a denormed
  // amount (a precondition the backend also enforces — MixedSourceLoad otherwise).
  it('does not render the partial-qty input when the order carries no amount', async () => {
    const user = userEvent.setup();
    currentTask = startedTask; // amount: null
    renderPane();

    await user.click(screen.getByTestId('task-complete-button'));
    await screen.findByTestId('task-complete-dialog');
    expect(screen.queryByTestId('task-complete-amount-input')).not.toBeInTheDocument();
  });

  it('completes with a partial quantity when the order carries an amount and one is entered', async () => {
    const user = userEvent.setup();
    currentTask = { ...startedTask, amount: 20 };
    renderPane();

    await user.click(screen.getByTestId('task-complete-button'));
    await screen.findByTestId('task-complete-dialog');

    await user.type(screen.getByTestId('task-complete-amount-input'), '5');
    await user.click(screen.getByTestId('task-complete-confirm'));

    // Accepting the suggestion (STR-01) plus a partial qty -- no destination
    // override fields, only id + amount.
    expect(completeMutate.mock.calls[0][0]).toEqual({ id: 2, amount: 5 });
  });

  // PT16: destinationUnitLoadId and destinationLocationId are mutually
  // exclusive -- toggling merge mode must never leak a location field.
  it('completes with a merge target when "Merge into unit load" is toggled', async () => {
    const user = userEvent.setup();
    // Row 23: the merge toggle is now gated on the same task.amount != null
    // precondition as the partial-qty field -- see the gating tests below.
    currentTask = { ...startedTask, amount: 20 };
    renderPane();

    await user.click(screen.getByTestId('task-complete-button'));
    await screen.findByTestId('task-complete-dialog');

    await user.click(screen.getByTestId('task-complete-merge-toggle'));
    await user.type(screen.getByTestId('task-complete-merge-ul-input'), '77');
    await waitFor(() => expect(screen.getByTestId('task-complete-confirm')).not.toBeDisabled());
    await user.click(screen.getByTestId('task-complete-confirm'));

    expect(completeMutate.mock.calls[0][0]).toEqual({ id: 2, destinationUnitLoadId: 77 });
  });

  // Row 23: mirrors the partial-qty gate directly above -- merging into an
  // existing unit load still needs a source amount to move.
  it('does not render the merge toggle when the order carries no amount', async () => {
    const user = userEvent.setup();
    currentTask = startedTask; // amount: null
    renderPane();

    await user.click(screen.getByTestId('task-complete-button'));
    await screen.findByTestId('task-complete-dialog');
    expect(screen.queryByTestId('task-complete-merge-toggle')).not.toBeInTheDocument();
  });

  it('disables confirm while the merge unit-load lookup is pending', async () => {
    // A promise that never settles during this test -- keeps mergeUlLookup.isLoading
    // true regardless of how many microtask ticks userEvent flushes.
    vi.mocked(api.get).mockImplementation((url: unknown) =>
      typeof url === 'string' && url.includes('/unit-loads/')
        ? new Promise(() => {})
        : Promise.resolve({
            content: [
              { id: 5, name: 'STR-01', area: { name: 'Storage A' } },
              { id: 6, name: 'STR-02', area: { name: 'Storage A' } },
            ],
            page: { number: 0, size: 200, totalElements: 2, totalPages: 1 },
          }),
    );
    const user = userEvent.setup();
    currentTask = { ...startedTask, amount: 20 };
    renderPane();

    await user.click(screen.getByTestId('task-complete-button'));
    await screen.findByTestId('task-complete-dialog');
    await user.click(screen.getByTestId('task-complete-merge-toggle'));
    await user.type(screen.getByTestId('task-complete-merge-ul-input'), '77');

    expect(screen.getByTestId('task-complete-confirm')).toBeDisabled();
  });

  it('disables confirm when the merge unit-load lookup errors', async () => {
    vi.mocked(api.get).mockImplementation((url: unknown) =>
      typeof url === 'string' && url.includes('/unit-loads/')
        ? Promise.reject(new Error('not found'))
        : Promise.resolve({
            content: [
              { id: 5, name: 'STR-01', area: { name: 'Storage A' } },
              { id: 6, name: 'STR-02', area: { name: 'Storage A' } },
            ],
            page: { number: 0, size: 200, totalElements: 2, totalPages: 1 },
          }),
    );
    const user = userEvent.setup();
    currentTask = { ...startedTask, amount: 20 };
    renderPane();

    await user.click(screen.getByTestId('task-complete-button'));
    await screen.findByTestId('task-complete-dialog');
    await user.click(screen.getByTestId('task-complete-merge-toggle'));
    await user.type(screen.getByTestId('task-complete-merge-ul-input'), '999');

    await screen.findByText('Unit load not found');
    expect(screen.getByTestId('task-complete-confirm')).toBeDisabled();
  });

  it('renders a successor chip linking to the chain continuation when successorId is set', () => {
    currentTask = { ...startedTask, state: 700, stateName: 'FINISHED', successorId: 99 };
    renderPane();
    expect(screen.getByTestId('task-successor-chip')).toHaveTextContent('Continues in #99');
  });
});
