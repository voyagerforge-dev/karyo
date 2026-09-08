import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type {
  ConsolidationGroupResponse,
  WaveDetailResponse,
  WaveOrderSummary,
  WaveResponse,
} from '@/types/waves';

const useWave = vi.fn();
const useWaveProgress = vi.fn();
const useReleaseWave = vi.fn();
const useCancelWave = vi.fn();
const useMarkGroupReady = vi.fn();
vi.mock('@/features/waves/use-waves', () => ({
  useWave: (...args: unknown[]) => useWave(...args),
  useWaveProgress: (...args: unknown[]) => useWaveProgress(...args),
  useReleaseWave: (...args: unknown[]) => useReleaseWave(...args),
  useCancelWave: (...args: unknown[]) => useCancelWave(...args),
  useMarkGroupReady: (...args: unknown[]) => useMarkGroupReady(...args),
}));

const { WaveDetail } = await import('../wave-detail');

const WAVE: WaveResponse = {
  id: 9,
  waveNumber: 'W-9',
  state: 'CONSOLIDATING',
  orderStrategyId: 1,
  wavePickMode: 'PICK_ONLY',
  shortageAction: 'SKIP',
  plannedReleaseAt: null,
  releasedAt: null,
  completedAt: null,
  totalOrders: 1,
  totalLines: 1,
  created: '2026-08-20T10:00:00Z',
  selectionStrategy: 'explicit',
  selectionRuleName: null,
};

const group = (over: Partial<ConsolidationGroupResponse>): ConsolidationGroupResponse => ({
  id: 1,
  destinationKey: 'ACME|Main St|1|10001|NYC|US',
  state: 'IN_PROGRESS',
  consolidationLocationId: null,
  totalPickOrders: 1,
  completedPickOrders: 0,
  sortSlot: '01',
  pickedAmount: 20,
  sortedAmount: 5,
  packedAmount: 0,
  shipmentId: null,
  ...over,
});

const unfilledOrder = (over: Partial<WaveOrderSummary>): WaveOrderSummary => ({
  orderId: 1,
  orderNumber: 'O-1',
  state: 'PROCESSABLE',
  prio: 0,
  customerName: null,
  deliveryDate: null,
  ...over,
});

const WAVE_DETAIL: WaveDetailResponse = {
  wave: WAVE,
  orders: [],
  shortages: [],
  groups: [group({}), group({ id: 2, sortSlot: '02', sortedAmount: 20, state: 'IN_PROGRESS' })],
  unfilledOrders: [],
};

function renderDetail() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <MemoryRouter>
        <WaveDetail waveId={9} canWrite />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  useWave.mockReturnValue({ data: WAVE_DETAIL, isLoading: false });
  useWaveProgress.mockReturnValue({ data: undefined, isLoading: false });
  useReleaseWave.mockReturnValue({ mutate: vi.fn(), isPending: false });
  useCancelWave.mockReturnValue({ mutate: vi.fn(), isPending: false });
  useMarkGroupReady.mockReturnValue({ mutate: vi.fn(), isPending: false });
});

describe('WaveDetail consolidation groups (sort station)', () => {
  it('shows slot and sorted/picked, disables READY while unsorted', () => {
    renderDetail();

    expect(screen.getByTestId('wave-group-slot-1')).toHaveTextContent('01');
    expect(screen.getByTestId('wave-group-sorted-1')).toHaveTextContent('5/20');
    expect(screen.getByTestId('wave-group-ready-btn-1')).toBeDisabled();
    expect(screen.getByTestId('wave-group-ready-btn-2')).toBeEnabled();
  });
});

describe('WaveDetail pack-out status and unfilled members (Bulk Allocation Sprint C)', () => {
  it('shows the group pack-out ratio and a link to its shipment once one exists', () => {
    useWave.mockReturnValue({
      data: {
        ...WAVE_DETAIL,
        groups: [group({ packedAmount: 25, sortedAmount: 35, shipmentId: 9 })],
      },
      isLoading: false,
    });
    renderDetail();

    expect(screen.getByTestId('wave-group-packed-1')).toHaveTextContent('25/35');
    const link = screen.getByTestId('wave-group-shipment-1');
    expect(link).toHaveAttribute('href', '/shipments?shipment=9');
  });

  it('does not link a shipment for a group that has not opened packing yet', () => {
    useWave.mockReturnValue({
      data: { ...WAVE_DETAIL, groups: [group({ packedAmount: 0, shipmentId: null })] },
      isLoading: false,
    });
    renderDetail();

    expect(screen.getByTestId('wave-group-packed-1')).toHaveTextContent('0/5');
    expect(screen.queryByTestId('wave-group-shipment-1')).not.toBeInTheDocument();
  });

  it('lists unfilled wave members when present, and hides the section when there are none', () => {
    useWave.mockReturnValue({
      data: {
        ...WAVE_DETAIL,
        unfilledOrders: [unfilledOrder({ orderId: 7, orderNumber: 'O-7', state: 'PICKED' })],
      },
      isLoading: false,
    });
    renderDetail();

    const table = screen.getByTestId('wave-unfilled-table');
    expect(table).toHaveTextContent('O-7');
  });

  it('hides the unfilled-members section when every member landed in a group', () => {
    renderDetail();
    expect(screen.queryByTestId('wave-unfilled-table')).not.toBeInTheDocument();
  });
});
