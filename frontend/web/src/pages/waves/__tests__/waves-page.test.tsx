import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { WaveDetailResponse, WaveResponse } from '@/types/waves';

// Radix Select needs these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

vi.mock('@/lib/api-client', () => ({ api: { get: vi.fn(), post: vi.fn() } }));

const mockIsEntitled = vi.fn();
const mockUseLicense = vi.fn(() => ({ isLoading: false, isEntitled: mockIsEntitled }));
vi.mock('@/features/license/use-license', () => ({ useLicense: () => mockUseLicense() }));

vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: ['fulfillment-read', 'fulfillment-write'],
    hasPermission: (p: string) => p.startsWith('fulfillment'),
    hasAnyPermission: () => true,
  })),
}));

vi.mock('@/pages/orders/use-orders', () => ({
  useOrderStrategies: vi.fn(() => ({ data: [{ id: 1, name: 'Default' }], isLoading: false })),
}));

const useWaves = vi.fn();
const useWave = vi.fn();
const useWaveProgress = vi.fn();
const useCreateWave = vi.fn();
const useReleaseWave = vi.fn();
const useCancelWave = vi.fn();
const useMarkGroupReady = vi.fn();
const useSelectionRules = vi.fn();
const useDeleteSelectionRule = vi.fn();
const useSelectionRuleFields = vi.fn();
const useCreateSelectionRule = vi.fn();
const useUpdateSelectionRule = vi.fn();
const usePreviewSelectionRule = vi.fn();
vi.mock('@/features/waves/use-waves', () => ({
  useWaves: (...args: unknown[]) => useWaves(...args),
  useWave: (...args: unknown[]) => useWave(...args),
  useWaveProgress: (...args: unknown[]) => useWaveProgress(...args),
  useCreateWave: (...args: unknown[]) => useCreateWave(...args),
  useReleaseWave: (...args: unknown[]) => useReleaseWave(...args),
  useCancelWave: (...args: unknown[]) => useCancelWave(...args),
  useMarkGroupReady: (...args: unknown[]) => useMarkGroupReady(...args),
  // RULES tab (rendered inside the same entitled board -- these back RulesTab/RuleEditor).
  useSelectionRules: (...args: unknown[]) => useSelectionRules(...args),
  useDeleteSelectionRule: (...args: unknown[]) => useDeleteSelectionRule(...args),
  useSelectionRuleFields: (...args: unknown[]) => useSelectionRuleFields(...args),
  useCreateSelectionRule: (...args: unknown[]) => useCreateSelectionRule(...args),
  useUpdateSelectionRule: (...args: unknown[]) => useUpdateSelectionRule(...args),
  usePreviewSelectionRule: (...args: unknown[]) => usePreviewSelectionRule(...args),
}));

const { WavesPage } = await import('../waves-page');

const WAVES: WaveResponse[] = [
  {
    id: 1,
    waveNumber: 'W-1',
    state: 'PLANNED',
    orderStrategyId: 1,
    wavePickMode: 'HYBRID',
    shortageAction: 'SKIP',
    plannedReleaseAt: null,
    releasedAt: null,
    completedAt: null,
    totalOrders: 3,
    totalLines: 9,
    created: '2026-08-20T10:00:00Z',
    selectionStrategy: null,
    selectionRuleName: null,
  },
  {
    id: 2,
    waveNumber: 'W-2',
    state: 'PICKING',
    orderStrategyId: 1,
    wavePickMode: 'HYBRID',
    shortageAction: 'SKIP',
    plannedReleaseAt: null,
    releasedAt: '2026-08-20T11:00:00Z',
    completedAt: null,
    totalOrders: 5,
    totalLines: 12,
    created: '2026-08-20T09:00:00Z',
    selectionStrategy: null,
    selectionRuleName: null,
  },
];

const WAVE_DETAIL: WaveDetailResponse = {
  wave: WAVES[0],
  orders: [
    {
      orderId: 10,
      orderNumber: 'DO-10',
      state: 'PROCESSABLE',
      prio: 50,
      customerName: 'Acme',
      deliveryDate: '2026-08-25',
    },
  ],
  shortages: [],
  groups: [],
  unfilledOrders: [],
};

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <WavesPage />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockIsEntitled.mockReturnValue(true);
  useWaves.mockReturnValue({
    data: { content: WAVES, page: { number: 0, size: 50, totalElements: 2, totalPages: 1 } },
    isLoading: false,
  });
  useWave.mockReturnValue({ data: WAVE_DETAIL, isLoading: false });
  useWaveProgress.mockReturnValue({ data: undefined, isLoading: false });
  useCreateWave.mockReturnValue({ mutate: vi.fn(), isPending: false });
  useReleaseWave.mockReturnValue({ mutate: vi.fn(), isPending: false });
  useCancelWave.mockReturnValue({ mutate: vi.fn(), isPending: false });
  useMarkGroupReady.mockReturnValue({ mutate: vi.fn(), isPending: false });
  useSelectionRules.mockReturnValue({
    data: { content: [], page: { number: 0, size: 50, totalElements: 0, totalPages: 0 } },
    isLoading: false,
  });
  useDeleteSelectionRule.mockReturnValue({ mutate: vi.fn(), isPending: false });
  useSelectionRuleFields.mockReturnValue({ data: [], isLoading: false });
  useCreateSelectionRule.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
  useUpdateSelectionRule.mockReturnValue({ mutateAsync: vi.fn(), isPending: false });
  usePreviewSelectionRule.mockReturnValue({ mutate: vi.fn(), isPending: false, data: undefined });
});

describe('WavesPage -- license gate', () => {
  it('renders only the locked panel when the advanced-fulfillment entitlement is absent, firing no wave queries', () => {
    mockIsEntitled.mockReturnValue(false);
    renderPage();

    expect(screen.getByTestId('waves-locked')).toBeInTheDocument();
    expect(screen.queryByTestId('waves-board')).not.toBeInTheDocument();
    expect(useWaves).not.toHaveBeenCalled();
  });

  it('renders the board when entitled, with no locked panel', () => {
    renderPage();

    expect(screen.getByTestId('waves-board')).toBeInTheDocument();
    expect(screen.queryByTestId('waves-locked')).not.toBeInTheDocument();
    expect(screen.getByText('W-1')).toBeInTheDocument();
    expect(screen.getByText('W-2')).toBeInTheDocument();
  });

  it('shows a loading state before the license query resolves, without rendering the board', () => {
    mockUseLicense.mockReturnValueOnce({ isLoading: true, isEntitled: mockIsEntitled });
    renderPage();

    expect(screen.queryByTestId('waves-board')).not.toBeInTheDocument();
    expect(screen.queryByTestId('waves-locked')).not.toBeInTheDocument();
    expect(useWaves).not.toHaveBeenCalled();
  });
});

describe('WavesPage -- board', () => {
  it('filter chips call useWaves with the selected state', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Picking' }));
    await waitFor(() => expect(useWaves).toHaveBeenLastCalledWith('PICKING'));
  });

  it('selecting a wave row shows the detail pane', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.queryByTestId('wave-detail')).not.toBeInTheDocument();
    await user.click(screen.getByText('W-1'));
    expect(screen.getByTestId('wave-detail')).toBeInTheDocument();
    expect(screen.getByText('DO-10')).toBeInTheDocument();
  });

  it('shows selection provenance in the detail header when the fields are set (IMPORTANT-3)', async () => {
    const user = userEvent.setup();
    useWave.mockReturnValue({
      data: {
        ...WAVE_DETAIL,
        wave: { ...WAVE_DETAIL.wave, selectionStrategy: 'rule-based', selectionRuleName: 'City-NYC' },
      },
      isLoading: false,
    });
    renderPage();

    await user.click(screen.getByText('W-1'));
    expect(screen.getByTestId('wave-selection-meta')).toHaveTextContent('rule-based / City-NYC');
  });

  it('shows an em dash for selection provenance when unset', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByText('W-1'));
    expect(screen.getByTestId('wave-selection-meta')).toHaveTextContent('—');
  });

  it('New wave button opens the create-wave dialog', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.queryByTestId('create-wave-dialog')).not.toBeInTheDocument();
    await user.click(screen.getByTestId('new-wave-button'));
    expect(screen.getByTestId('create-wave-dialog')).toBeInTheDocument();
  });

  it('Release button opens a confirm dialog and mutate fires on confirm', async () => {
    const releaseMutate = vi.fn();
    useReleaseWave.mockReturnValue({ mutate: releaseMutate, isPending: false });
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByText('W-1'));
    await user.click(screen.getByTestId('wave-release-button'));
    await user.click(screen.getByTestId('wave-release-confirm-btn'));
    expect(releaseMutate).toHaveBeenCalledWith(1, expect.anything());
  });
});

describe('WavesPage -- RULES tab', () => {
  it('the Rules tab switches the board to the rules master-detail, hiding New wave', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(screen.queryByTestId('rules-tab')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Rules' }));

    expect(screen.getByTestId('rules-tab')).toBeInTheDocument();
    expect(screen.queryByTestId('new-wave-button')).not.toBeInTheDocument();
    expect(useSelectionRules).toHaveBeenCalled();
  });

  it('switching back to Waves restores the wave master-detail', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Rules' }));
    await user.click(screen.getByRole('button', { name: 'Waves' }));

    expect(screen.queryByTestId('rules-tab')).not.toBeInTheDocument();
    expect(screen.getByText('W-1')).toBeInTheDocument();
  });
});
