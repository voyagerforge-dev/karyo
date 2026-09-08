import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createElement } from 'react';

const mockUseKpis = vi.fn();
const mockUseVolumeByCategory = vi.fn();
const mockUseReportDefinitions = vi.fn();
const mockCreateMutateAsync = vi.fn();
const mockDeleteMutateAsync = vi.fn();

vi.mock('@/features/insights/use-kpis', () => ({
  useKpis: (...args: unknown[]) => mockUseKpis(...args),
}));

vi.mock('@/features/insights/use-volume-by-category', () => ({
  useVolumeByCategory: (...args: unknown[]) => mockUseVolumeByCategory(...args),
}));

vi.mock('@/features/reports/use-report-definitions', () => ({
  useReportDefinitions: () => mockUseReportDefinitions(),
  useCreateReportDefinition: () => ({ mutateAsync: mockCreateMutateAsync, isPending: false }),
  useDeleteReportDefinition: () => ({ mutateAsync: mockDeleteMutateAsync, isPending: false }),
}));

vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: () => ({ hasPermission: () => true, hasAnyPermission: () => true, permissions: ['report-write'] }),
}));

beforeEach(() => {
  vi.clearAllMocks();
  mockUseKpis.mockReturnValue({
    isLoading: false, isError: false, isSuccess: true,
    data: {
      range: '30D', rangeLabel: 'Last 30 days',
      tiles: [
        { key: 'accuracy', label: 'Inventory accuracy', value: '98.4%', delta: '+0.6%', tone: 'up', series: [{ day: '2026-06-01', value: 98.1 }, { day: '2026-06-02', value: 98.4 }] },
        { key: 'utilization', label: 'Utilization', value: '72%', delta: null, tone: 'up', series: [] },
      ],
      chart: { outbound: [{ day: '2026-06-01', value: 100 }], received: [{ day: '2026-06-01', value: 90 }] },
    },
  });
  mockUseVolumeByCategory.mockReturnValue({ isLoading: false, isError: false, data: [] });
  mockUseReportDefinitions.mockReturnValue({ isLoading: false, isError: false, data: [] });
});

const { ReportsPage } = await import('@/pages/reports/reports-page');

describe('ReportsPage', () => {
  it('renders KPI tiles from the live model', async () => {
    render(createElement(ReportsPage));
    await waitFor(() => expect(screen.getByText('Inventory accuracy')).toBeInTheDocument());
    expect(screen.getByText('98.4%')).toBeInTheDocument();
    expect(screen.getByText('72%')).toBeInTheDocument();
  });

  it('renders delta when present and omits it when null', async () => {
    render(createElement(ReportsPage));
    await waitFor(() => expect(screen.getByText('+0.6%')).toBeInTheDocument());
    // utilization has delta=null — "vs prior" text should appear only once (accuracy tile)
    const vsPrior = screen.getAllByText('vs prior');
    expect(vsPrior).toHaveLength(1);
  });

  it('renders empty-state when tiles array is empty (fresh install, all-zero chart guard)', async () => {
    mockUseKpis.mockReturnValue({
      isLoading: false, isError: false, isSuccess: true,
      data: {
        range: '30D', rangeLabel: 'Last 30 days',
        tiles: [],
        chart: { outbound: [], received: [] },
      },
    });
    render(createElement(ReportsPage));
    await waitFor(() => expect(screen.getByText('No KPI data yet.')).toBeInTheDocument());
  });

  it('shows honest empty states for category breakdown and saved reports when there is no data', async () => {
    render(createElement(ReportsPage));
    await waitFor(() => expect(screen.getByText('Inventory accuracy')).toBeInTheDocument());
    expect(screen.getByText('Volume by category')).toBeInTheDocument();
    expect(screen.getByText('Saved reports')).toBeInTheDocument();
    expect(screen.getByText('No category data yet.')).toBeInTheDocument();
    expect(screen.getByText('No saved reports yet.')).toBeInTheDocument();
    expect(screen.queryByText('Electronics')).not.toBeInTheDocument();
    expect(screen.queryByText('Daily throughput summary')).not.toBeInTheDocument();
  });

  it('renders real category volume bars when data is present', async () => {
    mockUseVolumeByCategory.mockReturnValue({
      isLoading: false, isError: false,
      data: [
        { category: 'Electronics', volume: 80, lineCount: 10 },
        { category: 'Apparel', volume: 20, lineCount: 5 },
      ],
    });
    render(createElement(ReportsPage));
    await waitFor(() => expect(screen.getByText('Electronics')).toBeInTheDocument());
    expect(screen.getByText('Apparel')).toBeInTheDocument();
    expect(screen.getByText(/80\.0%/)).toBeInTheDocument();
    expect(screen.getByText(/20\.0%/)).toBeInTheDocument();
  });

  it('renders real saved reports and deletes one', async () => {
    mockUseReportDefinitions.mockReturnValue({
      isLoading: false, isError: false,
      data: [
        { id: 1, name: 'Daily throughput summary', reportType: 'throughput', params: '{}', owner: 'demo-seed', created: '2026-07-01T00:00:00Z' },
      ],
    });
    render(createElement(ReportsPage));
    await waitFor(() => expect(screen.getByText('Daily throughput summary')).toBeInTheDocument());
    expect(screen.getByText('throughput')).toBeInTheDocument();
    expect(screen.getByText('demo-seed')).toBeInTheDocument();

    const { default: userEvent } = await import('@testing-library/user-event');
    const user = userEvent.setup();
    mockDeleteMutateAsync.mockResolvedValue(undefined);
    await user.click(screen.getByLabelText('Delete Daily throughput summary'));
    await waitFor(() => expect(mockDeleteMutateAsync).toHaveBeenCalledWith(1));
  });
});
