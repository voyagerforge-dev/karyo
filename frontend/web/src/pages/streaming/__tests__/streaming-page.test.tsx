import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { StreamingStatusResponse, StreamOrder } from '@/types/streaming';

vi.mock('@/lib/api-client', () => ({ api: { get: vi.fn(), post: vi.fn() } }));

const mockIsEntitled = vi.fn();
const mockUseLicense = vi.fn(() => ({ isLoading: false, isEntitled: mockIsEntitled }));
vi.mock('@/features/license/use-license', () => ({ useLicense: () => mockUseLicense() }));

const mockHasPermission = vi.fn((p: string) => p.startsWith('fulfillment'));
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: ['fulfillment-read', 'fulfillment-write'],
    hasPermission: (p: string) => mockHasPermission(p),
    hasAnyPermission: () => true,
  })),
}));

const useStreamingStatus = vi.fn();
const useStreamingOrders = vi.fn();
const useRetryStreamingOrder = vi.fn();
vi.mock('@/features/streaming/use-streaming', () => ({
  useStreamingStatus: (...args: unknown[]) => useStreamingStatus(...args),
  useStreamingOrders: (...args: unknown[]) => useStreamingOrders(...args),
  useRetryStreamingOrder: (...args: unknown[]) => useRetryStreamingOrder(...args),
}));

const { StreamingPage } = await import('../streaming-page');

const STATUS: StreamingStatusResponse = {
  enabled: true,
  strategies: [
    {
      strategyId: 1,
      strategyName: 'Default',
      releaseMode: 'STREAM',
      timingStrategy: 'time-size',
      timingStrategyResolved: true,
      batchSize: 50,
      maxWaitSeconds: 30,
      abandonSeconds: 1800,
      eligible: 5,
      waiting: 3,
      escalated: 1,
      stalled: 0,
      pushFailed: 2,
      lastFlushAt: '2026-08-23T10:00:00Z',
      batchesLastHour: 4,
      releasedLastHour: 40,
      pushedLastHour: 40,
    },
    {
      strategyId: 2,
      strategyName: 'Unresolved',
      releaseMode: 'STREAM',
      timingStrategy: 'bogus',
      timingStrategyResolved: false,
      batchSize: 50,
      maxWaitSeconds: 30,
      abandonSeconds: 1800,
      eligible: 0,
      waiting: 0,
      escalated: 0,
      stalled: 0,
      pushFailed: 0,
      lastFlushAt: null,
      batchesLastHour: 0,
      releasedLastHour: 0,
      pushedLastHour: 0,
    },
  ],
};

const STALLED_ORDERS: StreamOrder[] = [
  {
    orderId: 100,
    orderNumber: 'DO-100',
    customerName: 'Acme',
    prio: 50,
    created: '2026-08-23T09:00:00Z',
    state: 300,
    orderStrategyId: 1,
    firstAttemptAt: '2026-08-23T09:05:00Z',
    escalatedAt: '2026-08-23T09:20:00Z',
    stalledAt: '2026-08-23T09:35:00Z',
    pendingLineCount: 2,
    bucket: 'STALLED',
  },
];

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <StreamingPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockIsEntitled.mockReturnValue(true);
  mockHasPermission.mockImplementation((p: string) => p.startsWith('fulfillment'));
  useStreamingStatus.mockReturnValue({ data: STATUS, isLoading: false });
  useStreamingOrders.mockReturnValue({ data: STALLED_ORDERS, isLoading: false });
  useRetryStreamingOrder.mockReturnValue({ mutate: vi.fn(), isPending: false });
});

describe('StreamingPage -- license gate', () => {
  it('renders only the locked panel when unentitled, firing no streaming queries', () => {
    mockIsEntitled.mockReturnValue(false);
    renderPage();

    expect(screen.getByTestId('streaming-locked')).toBeInTheDocument();
    expect(screen.queryByTestId('streaming-board')).not.toBeInTheDocument();
    expect(useStreamingStatus).not.toHaveBeenCalled();
    expect(useStreamingOrders).not.toHaveBeenCalled();
  });

  it('renders the board when entitled, with no locked panel', () => {
    renderPage();

    expect(screen.getByTestId('streaming-board')).toBeInTheDocument();
    expect(screen.queryByTestId('streaming-locked')).not.toBeInTheDocument();
  });

  it('shows a loading state before the license query resolves, without rendering the board', () => {
    mockUseLicense.mockReturnValueOnce({ isLoading: true, isEntitled: mockIsEntitled });
    renderPage();

    expect(screen.queryByTestId('streaming-board')).not.toBeInTheDocument();
    expect(screen.queryByTestId('streaming-locked')).not.toBeInTheDocument();
    expect(useStreamingStatus).not.toHaveBeenCalled();
  });
});

describe('StreamingPage -- board', () => {
  it('shows the enabled pill from status.enabled', () => {
    renderPage();
    expect(screen.getByTestId('streaming-enabled-pill')).toHaveTextContent('Enabled');
  });

  it('shows the disabled pill when status.enabled is false', () => {
    useStreamingStatus.mockReturnValue({ data: { ...STATUS, enabled: false }, isLoading: false });
    renderPage();
    expect(screen.getByTestId('streaming-enabled-pill')).toHaveTextContent('Disabled');
  });

  it('renders a strategy row per strategy, with a warning glyph when timing is unresolved', () => {
    renderPage();

    expect(screen.getByTestId('streaming-strategy-row-1')).toBeInTheDocument();
    expect(screen.getByTestId('streaming-strategy-row-2')).toBeInTheDocument();
    expect(screen.getByTestId('streaming-strategy-row-2')).toContainElement(
      screen.getByTitle('timing strategy not resolved'),
    );
    expect(
      screen.getByTestId('streaming-strategy-row-1').querySelector('[title]'),
    ).not.toBeInTheDocument();
  });

  it('shows the "no strategies" empty state when strategies is empty', () => {
    useStreamingStatus.mockReturnValue({ data: { enabled: true, strategies: [] }, isLoading: false });
    renderPage();
    expect(screen.getByText('No strategies stream yet')).toBeInTheDocument();
  });

  it('defaults the bucket chips to STALLED and calls useStreamingOrders with it', () => {
    renderPage();
    expect(useStreamingOrders).toHaveBeenCalledWith('STALLED');
  });

  it('clicking a bucket chip calls useStreamingOrders with the new bucket', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Waiting' }));
    await waitFor(() => expect(useStreamingOrders).toHaveBeenLastCalledWith('WAITING'));
  });

  it('clicking the Push failed chip calls useStreamingOrders with PUSH_FAILED', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Push failed' }));
    await waitFor(() => expect(useStreamingOrders).toHaveBeenLastCalledWith('PUSH_FAILED'));
  });

  it('renders the push-failed count in the strategies table', () => {
    renderPage();
    // Header order: Strategy, Mode, Batch/wait/abandon, Eligible, Waiting, Escalated, Stalled,
    // Push failed, Last flush, Batches/released/pushed -- Push failed is cell index 7. Asserting
    // on the whole row would also match the '2' inside the rendered lastFlushAt date string, so
    // this targets the Push failed cell specifically.
    const cells = within(screen.getByTestId('streaming-strategy-row-1')).getAllByRole('cell');
    expect(cells[7]).toHaveTextContent('2');
  });

  it('renders an order row per order', () => {
    renderPage();
    expect(screen.getByTestId('streaming-order-row-100')).toBeInTheDocument();
    expect(screen.getByText('DO-100')).toBeInTheDocument();
  });

  it('renders the order state as the orders screens name it, not the raw code', () => {
    renderPage();
    expect(screen.getByText('Processable')).toBeInTheDocument();
  });

  it('falls back to the raw code for a state outside the shared list', () => {
    useStreamingOrders.mockReturnValue({
      data: [{ ...STALLED_ORDERS[0], state: 500 }],
      isLoading: false,
    });
    renderPage();
    expect(screen.getByText('500')).toBeInTheDocument();
  });

  it('shows the "nothing in this bucket" empty state when orders is empty', () => {
    useStreamingOrders.mockReturnValue({ data: [], isLoading: false });
    renderPage();
    expect(screen.getByText('Nothing in this bucket')).toBeInTheDocument();
  });

  it('shows a Retry button on STALLED rows when the user has fulfillment-write', () => {
    renderPage();
    expect(screen.getByTestId('streaming-retry-100')).toBeInTheDocument();
  });

  it('hides the Retry button without fulfillment-write', () => {
    mockHasPermission.mockReturnValue(false);
    renderPage();
    expect(screen.queryByTestId('streaming-retry-100')).not.toBeInTheDocument();
  });

  it('hides the Retry button outside the STALLED bucket', async () => {
    const user = userEvent.setup();
    useStreamingOrders.mockReturnValue({
      data: [{ ...STALLED_ORDERS[0], bucket: 'WAITING' }],
      isLoading: false,
    });
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Waiting' }));
    expect(screen.queryByTestId('streaming-retry-100')).not.toBeInTheDocument();
  });

  it('hides the Retry button in the PUSH_FAILED bucket even with fulfillment-write', async () => {
    const user = userEvent.setup();
    useStreamingOrders.mockReturnValue({
      data: [{ ...STALLED_ORDERS[0], state: 300, stalledAt: null, bucket: 'PUSH_FAILED' }],
      isLoading: false,
    });
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Push failed' }));
    expect(screen.queryByTestId('streaming-retry-100')).not.toBeInTheDocument();
  });

  it('clicking Retry calls the mutation with the order id', async () => {
    const retryMutate = vi.fn();
    useRetryStreamingOrder.mockReturnValue({ mutate: retryMutate, isPending: false });
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('streaming-retry-100'));
    expect(retryMutate).toHaveBeenCalledWith(100);
  });

  it('disables the Retry button while the retry mutation is pending', () => {
    useRetryStreamingOrder.mockReturnValue({ mutate: vi.fn(), isPending: true });
    renderPage();

    expect(screen.getByTestId('streaming-retry-100')).toBeDisabled();
  });
});
