import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { ReplenishmentNeed, ReplenishmentScanResult } from '@/types/replenishment';

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

let mockRoles = ['task-read', 'task-write'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

const needsData: ReplenishmentNeed[] = [
  {
    fixAssignmentId: 1,
    locationId: 10,
    locationName: 'A-01-01',
    itemDataId: 100,
    itemDataNumber: 'SKU-1',
    currentAmount: 5,
    minAmount: 20,
    desiredAmount: 50,
    belowMin: true,
    hasOpenTask: false,
  },
];

const scanMutate = vi.fn();
let scanData: ReplenishmentScanResult | undefined;
let scanPending = false;

vi.mock('@/features/replenishment/use-replenishment', () => ({
  useReplenishmentNeeds: vi.fn(() => ({ data: needsData, isLoading: false })),
  useScanReplenishment: vi.fn(() => ({
    mutate: scanMutate,
    isPending: scanPending,
    data: scanData,
  })),
}));

import { ReplenishmentSection } from '../replenishment-section';

function renderSection() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReplenishmentSection />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  scanMutate.mockReset();
  scanData = undefined;
  scanPending = false;
  mockRoles = ['task-read', 'task-write'];
});

describe('ReplenishmentSection', () => {
  it('renders fix-face needs rows unchanged (regression)', () => {
    renderSection();
    const row = screen.getByTestId('replenishment-need-1');
    expect(within(row).getByText('A-01-01')).toBeInTheDocument();
    expect(within(row).getByText('SKU-1')).toBeInTheDocument();
    expect(within(row).getByText('Below min')).toBeInTheDocument();
  });

  it('does not render scan results before a scan has run', () => {
    renderSection();
    expect(screen.queryByTestId('replenishment-scan-results')).not.toBeInTheDocument();
  });

  it('renders a generated fix-face task row without an Area badge', () => {
    scanData = {
      generated: [
        {
          taskId: 10,
          orderNumber: 'TO-10',
          fixAssignmentId: 1,
          locationName: 'A-01-01',
          itemDataNumber: 'SKU-1',
          unitLoadId: 5,
          itemDataAreaId: null,
        },
      ],
      shortfalls: [],
    };
    renderSection();
    const row = screen.getByTestId('replenishment-generated-10');
    expect(within(row).getByText('TO-10')).toBeInTheDocument();
    expect(within(row).getByText('A-01-01')).toBeInTheDocument();
    expect(within(row).queryByText('Area')).not.toBeInTheDocument();
    expect(within(row).getByText('Fix face')).toBeInTheDocument();
  });

  it('renders a generated area task row with an Area badge and the real destination name', () => {
    scanData = {
      generated: [
        {
          taskId: 20,
          orderNumber: 'TO-20',
          fixAssignmentId: null,
          locationName: 'B-02-02',
          itemDataNumber: 'SKU-2',
          unitLoadId: 9,
          itemDataAreaId: 7,
        },
      ],
      shortfalls: [],
    };
    renderSection();
    const row = screen.getByTestId('replenishment-generated-20');
    expect(within(row).getByText('Area')).toBeInTheDocument();
    expect(within(row).getByText('B-02-02')).toBeInTheDocument();
  });

  it('renders an area shortfall row with the synthetic location prettified honestly', () => {
    scanData = {
      generated: [],
      shortfalls: [
        {
          fixAssignmentId: null,
          locationName: 'AREA-7',
          itemDataNumber: 'SKU-3',
          currentAmount: 3,
          minAmount: null,
          reason: 'NO_SOURCE',
          itemDataAreaId: 7,
        },
      ],
    };
    renderSection();
    const row = screen.getByTestId('replenishment-shortfall-0');
    expect(within(row).getByText('Area')).toBeInTheDocument();
    expect(within(row).getByText('Area 7')).toBeInTheDocument();
    // Absent minAmount renders as an em-dash, not a fabricated value.
    expect(within(row).getByText('—')).toBeInTheDocument();
    expect(within(row).getByText('NO_SOURCE')).toBeInTheDocument();
  });

  it('renders a fix-face shortfall row without prettifying its real location name', () => {
    scanData = {
      generated: [],
      shortfalls: [
        {
          fixAssignmentId: 2,
          locationName: 'A-02-01',
          itemDataNumber: 'SKU-4',
          currentAmount: 0,
          minAmount: 10,
          reason: 'NO_SOURCE',
          itemDataAreaId: null,
        },
      ],
    };
    renderSection();
    const row = screen.getByTestId('replenishment-shortfall-0');
    expect(within(row).getByText('A-02-01')).toBeInTheDocument();
    expect(within(row).queryByText('Area')).not.toBeInTheDocument();
    expect(within(row).getByText('Fix face')).toBeInTheDocument();
  });

  it('triggers a scan when the write role clicks Scan now', async () => {
    const user = userEvent.setup();
    renderSection();
    await user.click(screen.getByTestId('scan-replenishment-button'));
    expect(scanMutate).toHaveBeenCalledTimes(1);
  });
});
