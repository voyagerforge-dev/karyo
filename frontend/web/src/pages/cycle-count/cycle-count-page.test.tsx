import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type {
  CountSessionView,
  CountSessionSummaryView,
  CountOrderView,
  CountEntryView,
} from '@/types/cycle-count';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  ApiError: class ApiError extends Error {},
}));

const startMutate = vi.fn();
const submitMutate = vi.fn();
const acceptMutate = vi.fn();
const recountMutate = vi.fn();
const cancelOrderMutate = vi.fn();
const createCampaignMutate = vi.fn();
const closeCampaignMutate = vi.fn();

vi.mock('./use-cycle-count', () => ({
  useSessions: vi.fn(),
  useSession: vi.fn(),
  useOrderSession: vi.fn(),
  useStartCount: vi.fn(),
  useCountOrder: vi.fn(),
  useCountOrderEntry: vi.fn(),
  useSubmitCount: vi.fn(),
  useUnitLoadMissing: vi.fn(),
  useLocationEmpty: vi.fn(),
  useAccept: vi.fn(),
  useRecount: vi.fn(),
  useCancelOrder: vi.fn(),
  useCampaigns: vi.fn(),
  useCampaign: vi.fn(),
  useCreateCampaign: vi.fn(),
  useCloseCampaign: vi.fn(),
}));

import {
  useSessions,
  useSession,
  useOrderSession,
  useStartCount,
  useCountOrder,
  useCountOrderEntry,
  useSubmitCount,
  useUnitLoadMissing,
  useLocationEmpty,
  useAccept,
  useRecount,
  useCancelOrder,
  useCampaigns,
  useCampaign,
  useCreateCampaign,
  useCloseCampaign,
} from './use-cycle-count';
import { CycleCountPage } from './cycle-count-page';

// Session A: Open, blind count, mixed order states -- exercises the
// progress line (finished/counted counts) and both count-entry (state 50)
// and review (state 500) order-card branches.
// Full graph -- returned by useSession(1) (GET /count-sessions/{id}).
const sessionA: CountSessionView = {
  id: 1,
  sessionNumber: 'CS-0001',
  type: 'CYCLE',
  state: 100,
  orders: [
    { id: 101, orderNumber: 'CO-0101', sessionId: 1, locationId: 1, locationName: 'A-01', state: 50, lines: [] },
    { id: 102, orderNumber: 'CO-0102', sessionId: 1, locationId: 2, locationName: 'A-02', state: 500, lines: [] },
    { id: 103, orderNumber: 'CO-0103', sessionId: 1, locationId: 3, locationName: 'A-03', state: 700, lines: [] },
  ],
  campaignId: null,
};

// Session A's list-row summary -- returned by useSessions() (GET /count-sessions, paginated;
// order counts derived from sessionA's orders: 1 GENERATED(50), 1 COUNTED(500), 1 FINISHED(700)).
const sessionASummary: CountSessionSummaryView = {
  id: 1,
  sessionNumber: 'CS-0001',
  type: 'CYCLE',
  state: 100,
  campaignId: null,
  orderCount: 3,
  countedCount: 1,
  finishedCount: 1,
};

// Session B: Closed, contains order 7 -- the deep-link target.
// Full graph -- returned by useSession(2).
const sessionB: CountSessionView = {
  id: 2,
  sessionNumber: 'CS-0002',
  type: 'CYCLE',
  state: 700,
  orders: [
    { id: 7, orderNumber: 'CO-0007', sessionId: 2, locationId: 5, locationName: 'B-05', state: 50, lines: [] },
  ],
  campaignId: null,
};

// Session B's list-row summary.
const sessionBSummary: CountSessionSummaryView = {
  id: 2,
  sessionNumber: 'CS-0002',
  type: 'CYCLE',
  state: 700,
  campaignId: null,
  orderCount: 1,
  countedCount: 0,
  finishedCount: 0,
};

const entryFor101: CountEntryView = {
  id: 101,
  orderNumber: 'CO-0101',
  locationName: 'A-01',
  lines: [
    {
      lineId: 1,
      itemDataNumber: 'SKU-1',
      lotNumber: null,
      serialNumber: null,
      unitLoadId: null,
      unitLoadLabel: null,
      counted: false,
    },
  ],
};

const reviewFor102: CountOrderView = {
  id: 102,
  orderNumber: 'CO-0102',
  sessionId: 1,
  locationId: 2,
  locationName: 'A-02',
  state: 500,
  lines: [
    {
      id: 2,
      stockUnitId: 1,
      itemDataNumber: 'SKU-2',
      lotNumber: null,
      plannedAmount: 5,
      countedAmount: 5,
      state: 500,
    },
  ],
};

const entryFor7: CountEntryView = {
  id: 7,
  orderNumber: 'CO-0007',
  locationName: 'B-05',
  lines: [
    {
      lineId: 3,
      itemDataNumber: 'SKU-3',
      lotNumber: null,
      serialNumber: null,
      unitLoadId: null,
      unitLoadLabel: null,
      counted: false,
    },
  ],
};

function renderPage(initialEntries: string[] = ['/cycle-count']) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={initialEntries}>
        <CycleCountPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  startMutate.mockClear();
  submitMutate.mockClear();
  acceptMutate.mockClear();
  recountMutate.mockClear();
  cancelOrderMutate.mockClear();
  createCampaignMutate.mockClear();
  closeCampaignMutate.mockClear();

  // St1: CampaignsCard + the start-form's campaign select both call useCampaigns() --
  // default to an empty list so existing tests (predating campaigns) render unchanged.
  vi.mocked(useCampaigns).mockReturnValue({
    data: [],
    isLoading: false,
  } as unknown as ReturnType<typeof useCampaigns>);

  vi.mocked(useCampaign).mockReturnValue({
    data: undefined,
    isLoading: false,
  } as unknown as ReturnType<typeof useCampaign>);

  vi.mocked(useCreateCampaign).mockReturnValue({
    mutate: createCampaignMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useCreateCampaign>);

  vi.mocked(useCloseCampaign).mockReturnValue({
    mutate: closeCampaignMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useCloseCampaign>);

  vi.mocked(useSessions).mockReturnValue({
    data: [sessionASummary, sessionBSummary],
    isLoading: false,
  } as ReturnType<typeof useSessions>);

  vi.mocked(useSession).mockImplementation(
    (id?: number) =>
      ({
        data: id === 1 ? sessionA : id === 2 ? sessionB : undefined,
        isLoading: false,
      }) as ReturnType<typeof useSession>,
  );

  // The `?order=` deep link resolves order 7 -> session 2 (session B) via a direct lookup,
  // now that the sessions list carries no nested orders to scan.
  vi.mocked(useOrderSession).mockImplementation(
    (id?: number) =>
      ({
        data: id === 7 ? 2 : undefined,
        isFetched: id != null,
      }) as ReturnType<typeof useOrderSession>,
  );

  vi.mocked(useStartCount).mockReturnValue({
    mutate: startMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useStartCount>);

  vi.mocked(useCountOrderEntry).mockImplementation(
    (id?: number) =>
      ({
        data: id === 101 ? entryFor101 : id === 7 ? entryFor7 : undefined,
        isLoading: false,
      }) as ReturnType<typeof useCountOrderEntry>,
  );

  vi.mocked(useCountOrder).mockImplementation(
    (id?: number) =>
      ({
        data: id === 102 ? reviewFor102 : undefined,
        isLoading: false,
      }) as ReturnType<typeof useCountOrder>,
  );

  vi.mocked(useSubmitCount).mockReturnValue({
    mutate: submitMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useSubmitCount>);

  vi.mocked(useUnitLoadMissing).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useUnitLoadMissing>);

  vi.mocked(useLocationEmpty).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useLocationEmpty>);

  vi.mocked(useAccept).mockReturnValue({
    mutate: acceptMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useAccept>);

  vi.mocked(useRecount).mockReturnValue({
    mutate: recountMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useRecount>);

  vi.mocked(useCancelOrder).mockReturnValue({
    mutate: cancelOrderMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useCancelOrder>);
});

describe('CycleCountPage', () => {
  it('lists sessions with state/progress and selects into the session workspace', async () => {
    const user = userEvent.setup();
    renderPage();

    const rowA = screen.getByTestId('session-row-1');
    expect(within(rowA).getByText('CS-0001')).toBeInTheDocument();
    expect(within(rowA).getByText('Open')).toBeInTheDocument();
    expect(within(rowA).getByText('1/3 done, 1 in review')).toBeInTheDocument();

    const rowB = screen.getByTestId('session-row-2');
    expect(within(rowB).getByText('Closed')).toBeInTheDocument();

    await user.click(rowA);

    expect(screen.getByTestId('order-row-101')).toBeInTheDocument();
    expect(screen.getByTestId('order-row-102')).toBeInTheDocument();
    expect(screen.getByTestId('order-row-103')).toBeInTheDocument();
  });

  it('order card opens count entry (state 50) and review (state 500) in the pane', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('session-row-1'));

    await user.click(screen.getByTestId('order-row-101'));
    expect(screen.getByTestId('count-entry-table')).toBeInTheDocument();

    // Back to session, then open the review order.
    await user.click(screen.getByText('← Back to session'));
    expect(screen.getByTestId('order-row-102')).toBeInTheDocument();

    await user.click(screen.getByTestId('order-row-102'));
    expect(screen.getByTestId('review-panel')).toBeInTheDocument();
  });

  // St7: ReviewPanel's own Cancel affordance (state 500 -- the accept/recount/cancel trio).
  it('review-panel cancel button confirms then calls useCancelOrder with the order id', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('session-row-1'));
    await user.click(screen.getByTestId('order-row-102'));
    expect(screen.getByTestId('review-panel')).toBeInTheDocument();

    await user.click(screen.getByTestId('cancel-order-button'));
    expect(screen.getByText('Cancel this count order?')).toBeInTheDocument();
    expect(cancelOrderMutate).not.toHaveBeenCalled();

    await user.click(screen.getByText('Cancel order'));
    expect(cancelOrderMutate).toHaveBeenCalledWith(102, expect.anything());
  });

  it('?order= deep link selects session and order', async () => {
    renderPage(['/cycle-count?order=7']);

    // Session B (the one containing order 7) is auto-selected and its
    // count-entry pane opens directly (order 7 is state 50 -> count view,
    // replacing the order-card list -- same as the old SessionDrawer).
    expect(await screen.findByTestId('count-entry-table')).toBeInTheDocument();
    expect(screen.getByTestId('session-row-2')).toHaveAttribute('data-active', 'true');
  });

  it('start-count form creates a session and selects it', async () => {
    const user = userEvent.setup();
    // What useStartCount's mutationFn actually returns now (the summary projection --
    // handleStart only reads `.id` off it).
    const newSessionSummary: CountSessionSummaryView = {
      id: 99,
      sessionNumber: 'CS-0099',
      type: 'CYCLE',
      state: 100,
      campaignId: null,
      orderCount: 0,
      countedCount: 0,
      finishedCount: 0,
    };
    // What useSession(99) (GET /count-sessions/99, the full graph) then returns once selected.
    const newSessionGraph: CountSessionView = {
      id: 99,
      sessionNumber: 'CS-0099',
      type: 'CYCLE',
      state: 100,
      orders: [],
      campaignId: null,
    };
    startMutate.mockImplementation((_body, opts) => {
      opts?.onSuccess?.(newSessionSummary);
    });
    vi.mocked(useSession).mockImplementation(
      (id?: number) =>
        ({
          data: id === 1 ? sessionA : id === 2 ? sessionB : id === 99 ? newSessionGraph : undefined,
          isLoading: false,
        }) as ReturnType<typeof useSession>,
    );
    renderPage();

    await user.click(screen.getByText('New count'));

    await user.type(screen.getByTestId('location-ids-input'), '1, 2');
    await user.click(screen.getByTestId('start-count-button'));

    expect(startMutate).toHaveBeenCalledTimes(1);
    expect(screen.getByText('No count orders in this session.')).toBeInTheDocument();
  });

  // St2: location name-pattern scope -- the form's pattern input alone enables
  // the Start button and is passed through to the mutation.
  it('start-count form passes a location name pattern with no ids/area filled in', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByText('New count'));

    const startButton = screen.getByTestId('start-count-button');
    expect(startButton).toBeDisabled();

    await user.type(screen.getByTestId('location-pattern-input'), 'A-01-%');
    expect(startButton).not.toBeDisabled();

    await user.click(startButton);

    expect(startMutate).toHaveBeenCalledWith(
      expect.objectContaining({ locationNamePattern: 'A-01-%', locationIds: undefined, areaId: undefined }),
      expect.anything(),
    );
  });

  // St5: full-inventory (END_OF_PERIOD) start type.
  describe('full inventory (St5)', () => {
    it('selecting Full inventory hides the scope inputs and enables Start with no scope typed', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByText('New count'));
      expect(screen.getByTestId('location-ids-input')).toBeInTheDocument();
      expect(screen.getByTestId('start-count-button')).toBeDisabled();

      await user.click(screen.getByTestId('count-type-select'));
      await user.click(screen.getByText('Full inventory'));

      expect(screen.queryByTestId('location-ids-input')).not.toBeInTheDocument();
      expect(screen.queryByTestId('area-id-input')).not.toBeInTheDocument();
      expect(screen.queryByTestId('location-pattern-input')).not.toBeInTheDocument();
      expect(screen.getByTestId('full-inventory-note')).toBeInTheDocument();
      // No scope to type -- every location this client owns IS the scope.
      expect(screen.getByTestId('start-count-button')).not.toBeDisabled();

      await user.click(screen.getByTestId('start-count-button'));
      expect(startMutate).toHaveBeenCalledWith(
        expect.objectContaining({
          type: 'END_OF_PERIOD',
          locationIds: undefined,
          areaId: undefined,
          locationNamePattern: undefined,
        }),
        expect.anything(),
      );
    });

    it('the campaign select follows the count type (END_OF_PERIOD campaigns only)', async () => {
      vi.mocked(useCampaigns).mockReturnValue({
        data: [
          {
            id: 5,
            campaignNumber: 'CC-0001',
            name: 'Q3 sweep',
            type: 'CYCLE',
            state: 100,
            started: null,
            ended: null,
          },
          {
            id: 7,
            campaignNumber: 'CC-0003',
            name: 'Annual count',
            type: 'END_OF_PERIOD',
            state: 100,
            started: null,
            ended: null,
          },
        ],
        isLoading: false,
      } as unknown as ReturnType<typeof useCampaigns>);
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByText('New count'));
      await user.click(screen.getByTestId('count-type-select'));
      await user.click(screen.getByText('Full inventory'));
      await user.click(screen.getByTestId('campaign-select'));

      expect(screen.getByText('CC-0003 — Annual count')).toBeInTheDocument();
      expect(screen.queryByText('CC-0001 — Q3 sweep')).not.toBeInTheDocument();
    });

    it('session row shows a Full inventory badge and the skipped count', () => {
      const fullSessionSummary: CountSessionSummaryView = {
        id: 3,
        sessionNumber: 'CS-0003',
        type: 'END_OF_PERIOD',
        state: 100,
        campaignId: null,
        orderCount: 1,
        countedCount: 0,
        finishedCount: 1,
        skippedLocations: ['A-01', 'A-02'],
      };
      vi.mocked(useSessions).mockReturnValue({
        data: [sessionASummary, fullSessionSummary],
        isLoading: false,
      } as ReturnType<typeof useSessions>);
      renderPage();

      const row = screen.getByTestId('session-row-3');
      expect(within(row).getByText('Full inventory')).toBeInTheDocument();
      expect(within(row).getByText('1/1 done · 2 skipped')).toBeInTheDocument();

      // A plain CYCLE session gets no type badge and no skipped fragment.
      const cycleRow = screen.getByTestId('session-row-1');
      expect(within(cycleRow).queryByText('Full inventory')).not.toBeInTheDocument();
      expect(within(cycleRow).getByText('1/3 done, 1 in review')).toBeInTheDocument();
    });
  });

  // I-2 (final review): SessionDetail had no `key`, so switching sessions left
  // activeOrderId/activeMode (an active count-entry form) mounted from the
  // previously-selected session, rendering session B's pane with session A's form.
  it('switching sessions clears an active count-entry form from the previous session (I-2)', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('session-row-1'));
    await user.click(screen.getByTestId('order-row-101'));
    expect(screen.getByTestId('count-entry-table')).toBeInTheDocument();

    // Switch to a different session -- must NOT carry over session A's open form.
    await user.click(screen.getByTestId('session-row-2'));

    expect(screen.queryByTestId('count-entry-table')).not.toBeInTheDocument();
    expect(screen.getByTestId('order-row-7')).toBeInTheDocument();
  });

  it('deepLinkHandledRef prevents redundant deep link processing on effect re-run', async () => {
    // The deepLinkHandledRef guard ensures the deep link effect only processes
    // the deep link once, preventing issues if the effect re-runs due to
    // dependency changes (e.g., data refetches).
    //
    // Without the guard, the effect could re-run and re-apply the deep link,
    // causing redundant state updates or hijacking user selections.
    // This test verifies the guard works by confirming deep link is applied
    // exactly once and the session stays selected.
    renderPage(['/cycle-count?order=7']);

    // Deep link applies: sessionB is auto-selected and order entry opens
    const entryForm = await screen.findByTestId('count-entry-table');
    expect(entryForm).toBeInTheDocument();
    expect(screen.getByTestId('session-row-2')).toHaveAttribute('data-active', 'true');

    // The deep link order should be passed to SessionDetail (via deepLinkOrderId prop)
    // and the order entry should be displayed. If the guard is missing and the
    // effect re-runs, it might cause redundant state updates or unexpected behavior.
    //
    // With the guard in place, the effect only runs once, consuming the deep link
    // parameter and preventing re-application of the logic.
    const sessionDetail = screen.getByTestId('session-detail');
    expect(sessionDetail).toBeInTheDocument();

    // Verify the deep-linked order's entry form is displayed (state is correct)
    expect(screen.getByTestId('count-entry-table')).toBeInTheDocument();
  });

  // St1: campaigns card -- list + create + close, and the start-form campaign
  // select offers only OPEN(100) CYCLE campaigns.
  describe('campaigns card (St1)', () => {
    const openCycle = {
      id: 5,
      campaignNumber: 'CC-0001',
      name: 'Q3 sweep',
      type: 'CYCLE',
      state: 100 as const,
      started: '2026-07-01T00:00:00Z',
      ended: null,
    };
    const closedCycle = {
      id: 6,
      campaignNumber: 'CC-0002',
      name: 'Q2 sweep',
      type: 'CYCLE',
      state: 700 as const,
      started: '2026-04-01T00:00:00Z',
      ended: '2026-04-30T00:00:00Z',
    };
    const openEndOfPeriod = {
      id: 7,
      campaignNumber: 'CC-0003',
      name: 'Annual count',
      type: 'END_OF_PERIOD',
      state: 100 as const,
      started: '2026-01-01T00:00:00Z',
      ended: null,
    };

    beforeEach(() => {
      vi.mocked(useCampaigns).mockReturnValue({
        data: [openCycle, closedCycle, openEndOfPeriod],
        isLoading: false,
      } as unknown as ReturnType<typeof useCampaigns>);
    });

    it('lists campaigns with an Open/Closed pill and a Close button only on OPEN ones', () => {
      renderPage();

      const openRow = screen.getByTestId('campaign-row-5');
      expect(within(openRow).getByText('Open')).toBeInTheDocument();
      expect(within(openRow).getByTestId('campaign-close-5')).toBeInTheDocument();

      const closedRow = screen.getByTestId('campaign-row-6');
      expect(within(closedRow).getByText('Closed')).toBeInTheDocument();
      expect(within(closedRow).queryByTestId('campaign-close-6')).not.toBeInTheDocument();
    });

    it('create button calls useCreateCampaign with the form name/type', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.type(screen.getByTestId('campaign-name-input'), 'New sweep');
      await user.click(screen.getByTestId('campaign-create-button'));

      expect(createCampaignMutate).toHaveBeenCalledWith(
        { name: 'New sweep', type: 'CYCLE' },
        expect.anything(),
      );
    });

    it('close button calls useCloseCampaign with the campaign id', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByTestId('campaign-close-5'));
      expect(closeCampaignMutate).toHaveBeenCalledWith(5);
    });

    it('expanding a row fetches and shows its rollup line', async () => {
      vi.mocked(useCampaign).mockReturnValue({
        data: {
          ...openCycle,
          sessions: 2,
          ordersByState: { generated: 0, counted: 0, finished: 2, cancelled: 0 },
          discrepancyLines: 1,
        },
        isLoading: false,
      } as unknown as ReturnType<typeof useCampaign>);
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByTestId('campaign-toggle-5'));
      expect(screen.getByTestId('campaign-rollup-5')).toHaveTextContent(
        '2 sessions · 2/2 orders finished · 1 discrepancy',
      );
    });

    it('start-form campaign select offers only OPEN CYCLE campaigns', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByText('New count'));
      await user.click(screen.getByTestId('campaign-select'));

      expect(screen.getByText('CC-0001 — Q3 sweep')).toBeInTheDocument();
      expect(screen.queryByText('CC-0002 — Q2 sweep')).not.toBeInTheDocument();
      expect(screen.queryByText('CC-0003 — Annual count')).not.toBeInTheDocument();
    });

    it('start-count passes the selected campaignId', async () => {
      const user = userEvent.setup();
      renderPage();

      await user.click(screen.getByText('New count'));
      await user.type(screen.getByTestId('location-ids-input'), '1');
      await user.click(screen.getByTestId('campaign-select'));
      await user.click(screen.getByText('CC-0001 — Q3 sweep'));
      await user.click(screen.getByTestId('start-count-button'));

      expect(startMutate).toHaveBeenCalledWith(
        expect.objectContaining({ campaignId: 5 }),
        expect.anything(),
      );
    });
  });
});
