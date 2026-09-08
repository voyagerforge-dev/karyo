import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { createElement } from 'react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { ControlPrefsProvider } from '@/pages/home/ops/control-prefs-provider';
import { SampleDataProvider } from '@/features/sample-data/sample-data-provider';
import type { OpsData } from '@/pages/home/ops/use-ops-data';

const mockOpsData = vi.fn<() => OpsData>();
const mockDemoEnabled = vi.fn<() => boolean>();

vi.mock('@/pages/home/ops/use-ops-data', () => ({
  useOpsData: () => mockOpsData(),
}));

vi.mock('@/features/sample-data/use-demo-enabled', () => ({
  useDemoEnabled: () => mockDemoEnabled(),
}));

const { OperationsControl } = await import('@/pages/home/ops/operations-control');

// OperationsControl now renders SampleDataCard, which reads useSampleData() --
// it needs a SampleDataProvider (and the QueryClientProvider that provider
// itself depends on) in the tree, same as the real AppShell wiring.
function renderDashboard() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    createElement(
      QueryClientProvider,
      { client: queryClient },
      createElement(
        SampleDataProvider,
        null,
        createElement(ControlPrefsProvider, null, createElement(OperationsControl)),
      ),
    ),
  );
}

const BASE_OPS_DATA: OpsData = {
  kpis: [
    {
      label: 'Pick accuracy',
      value: '99.2%',
      points: '0,14 8,15 16,11 24,13 32,9 40,10 50,7 58,6',
      tone: 'up',
      deltaArrow: '▲',
      deltaText: '0.3',
      context: '',
    },
    {
      label: 'Units / hr',
      value: '142',
      points: '0,17 8,14 16,15 24,11 32,12 40,9 50,7 58,8',
      tone: 'up',
      deltaArrow: '▲',
      deltaText: '8',
      context: '',
    },
    {
      label: 'Dock-to-stock',
      value: '38m',
      points: '',
      tone: 'up',
      deltaArrow: '▼',
      deltaText: '2m',
      context: '',
    },
    {
      label: 'Utilization',
      value: '76%',
      points: '',
      tone: 'up',
      deltaArrow: '▲',
      deltaText: '',
      context: '',
    },
  ],
  throughput: {
    bars: [
      { label: 'Mon', pct: 67, isPeak: false },
      { label: 'Tue', pct: 100, isPeak: true },
      { label: 'Wed', pct: 50, isPeak: false },
    ],
    peakText: 'PEAK TUE 180',
    avgText: 'AVG 130',
  },
  zone: {
    cells: [
      { color: 'rgb(var(--acc))', title: 'A · A-01 — occupied' },
      { color: '#23201A', title: 'A · A-02 — empty' },
      { color: '#FF6A45', title: 'B · B-01 — locked' },
    ],
    title: 'Zone occupancy',
    sub: 'FACILITY 50% FULL',
    badgeText: '1 LOCKED',
    badgeTone: 'danger',
    legendUnit: 'STATE',
    legend: [
      { color: 'rgb(var(--acc))', label: 'Occupied' },
      { color: '#23201A', label: 'Empty' },
      { color: '#FF6A45', label: 'Locked' },
    ],
  },
  exceptions: [
    { type: 'EXPIRY RISK', detail: 'lot 42 · 4 lots expiring', age: '2m', severity: 'danger' },
  ],
  monitorsEntitled: true,
  monitorsLoading: false,
  isLoading: false,
};

describe('OperationsControl', () => {
  beforeEach(() => {
    // Default: demo mode off, matching the real default (KARYO_DEMO unset).
    // Individual tests below override this to assert the card's presence.
    mockDemoEnabled.mockReturnValue(false);
  });

  it('renders the 4 real KPI labels', () => {
    mockOpsData.mockReturnValue(BASE_OPS_DATA);
    renderDashboard();
    expect(screen.getByText('Pick accuracy')).toBeInTheDocument();
    expect(screen.getByText('Units / hr')).toBeInTheDocument();
    expect(screen.getByText('Dock-to-stock')).toBeInTheDocument();
    expect(screen.getByText('Utilization')).toBeInTheDocument();
  });

  it('renders throughput bars with no Shift toggle', () => {
    mockOpsData.mockReturnValue(BASE_OPS_DATA);
    renderDashboard();
    expect(screen.getByText('Mon')).toBeInTheDocument();
    expect(screen.getByText('Tue')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Shift' })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Day' })).not.toBeInTheDocument();
  });

  it('renders real zone cells + facility pct', () => {
    mockOpsData.mockReturnValue(BASE_OPS_DATA);
    renderDashboard();
    expect(screen.getByTitle('A · A-01 — occupied')).toBeInTheDocument();
    expect(screen.getByTitle('B · B-01 — locked')).toBeInTheDocument();
    expect(screen.getByText(/FACILITY 50% FULL/)).toBeInTheDocument();
  });

  it('maps a firing alert into an exception row', () => {
    mockOpsData.mockReturnValue(BASE_OPS_DATA);
    renderDashboard();
    expect(screen.getByText('EXPIRY RISK')).toBeInTheDocument();
    expect(screen.getByText('lot 42 · 4 lots expiring')).toBeInTheDocument();
  });

  it('shows a locked empty-state for exceptions when monitors is not entitled', () => {
    mockOpsData.mockReturnValue({ ...BASE_OPS_DATA, exceptions: [], monitorsEntitled: false });
    renderDashboard();
    expect(screen.getByTestId('exceptions-locked')).toBeInTheDocument();
    expect(screen.queryByText('EXPIRY RISK')).not.toBeInTheDocument();
  });

  it('shows neither the locked panel nor the empty state while monitors is still resolving', () => {
    mockOpsData.mockReturnValue({
      ...BASE_OPS_DATA,
      exceptions: [],
      monitorsEntitled: false,
      monitorsLoading: true,
    });
    renderDashboard();
    expect(screen.getByTestId('exceptions-loading')).toBeInTheDocument();
    expect(screen.queryByTestId('exceptions-locked')).not.toBeInTheDocument();
    expect(screen.queryByText('No open exceptions.')).not.toBeInTheDocument();
  });

  it('never renders the deleted mock tiles', () => {
    mockOpsData.mockReturnValue(BASE_OPS_DATA);
    renderDashboard();
    expect(screen.queryByText(/active waves/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/dock doors/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/picker/i)).not.toBeInTheDocument();
    expect(screen.queryByText(/copilot/i)).not.toBeInTheDocument();
  });

  // Task 11 (defect-burndown): SampleDataCard now mounts only when the
  // /api/v1/demo/status probe resolves enabled -- previously unconditional,
  // a dead surface on any deployment with KARYO_DEMO off (the default).
  it('hides SampleDataCard when the demo-status probe resolves disabled (or fails)', () => {
    mockOpsData.mockReturnValue(BASE_OPS_DATA);
    mockDemoEnabled.mockReturnValue(false);
    renderDashboard();
    expect(screen.queryByTestId('sample-data-card')).not.toBeInTheDocument();
  });

  it('shows SampleDataCard when the demo-status probe resolves enabled', () => {
    mockOpsData.mockReturnValue(BASE_OPS_DATA);
    mockDemoEnabled.mockReturnValue(true);
    renderDashboard();
    expect(screen.getByTestId('sample-data-card')).toBeInTheDocument();
  });
});
