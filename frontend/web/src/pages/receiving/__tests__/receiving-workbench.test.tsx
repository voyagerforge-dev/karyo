import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter, Routes, Route } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { AsnResponse, GoodsReceiptResponse } from '@/types/receiving';
import { GOODS_RECEIPT_TYPE } from '@/types/receiving';

// ReceiveForm's pickers (ProductPicker/LocationPicker) hit the network --
// keep the API client mocked so they resolve to an empty page instantly.
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

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

const navigateMock = vi.fn();
vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return { ...actual, useNavigate: () => navigateMock };
});

const finishMutate = vi.fn();
const resumeMutate = vi.fn();
const receiveMutate = vi.fn();

vi.mock('../use-receiving', () => ({
  useGoodsReceipt: vi.fn(),
  useFinishReceipt: vi.fn(() => ({ mutate: finishMutate, isPending: false })),
  useResumeReceipt: vi.fn(() => ({ mutate: resumeMutate, isPending: false })),
  useReceiveLine: vi.fn(() => ({ mutate: receiveMutate, isPending: false })),
}));

vi.mock('../../asns/use-asns', () => ({
  useAsnsByIds: vi.fn(),
}));

let mockRoles = ['order-read', 'order-write'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

import { useGoodsReceipt } from '../use-receiving';
import { useAsnsByIds } from '../../asns/use-asns';
import { ReceivingWorkbench } from '../receiving-workbench';

function baseReceipt(overrides: Partial<GoodsReceiptResponse> = {}): GoodsReceiptResponse {
  return {
    id: 5,
    receiptNumber: 'GR-3005',
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
    lines: [],
    created: '2026-07-20T08:00:00Z',
    modified: '2026-07-20T08:00:00Z',
    ...overrides,
  };
}

function renderWorkbench(receipt: GoodsReceiptResponse, asns: AsnResponse[] = []) {
  vi.mocked(useGoodsReceipt).mockReturnValue({
    data: receipt,
    isLoading: false,
  } as ReturnType<typeof useGoodsReceipt>);
  vi.mocked(useAsnsByIds).mockReturnValue({
    data: asns,
    isLoading: false,
  } as ReturnType<typeof useAsnsByIds>);

  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter initialEntries={[`/receiving/${receipt.id}`]}>
        <Routes>
          <Route path="/receiving/:id" element={<ReceivingWorkbench />} />
        </Routes>
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  finishMutate.mockClear();
  resumeMutate.mockClear();
  receiveMutate.mockClear();
  navigateMock.mockClear();
  mockRoles = ['order-read', 'order-write'];
});

describe('ReceivingWorkbench', () => {
  it('shows the real lock label and note instead of a blanket QA badge', () => {
    const receipt = baseReceipt({
      lines: [
        {
          id: 51,
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
          lockType: 202,
          note: 'short-dated',
          qaHold: true,
          reversed: false,
          reversedAt: null,
          storageStrategyId: null,
        },
      ],
    });
    renderWorkbench(receipt);

    const row = screen.getByTestId('received-line-51');
    expect(within(row).getByText('LOT EXPIRED')).toBeInTheDocument();
    expect(within(row).getByText('short-dated')).toBeInTheDocument();
    expect(screen.queryByText('QA HOLD')).not.toBeInTheDocument();
  });

  it('unlocked lines render an honest dash', () => {
    const receipt = baseReceipt({
      lines: [
        {
          id: 52,
          asnLineId: null,
          itemDataId: 6,
          itemDataNumber: 'DEMO-KEYBOARD',
          amount: 4,
          locationId: 8,
          locationName: 'STR-02',
          unitLoadLabel: 'UL-2',
          stockUnitId: 2,
          unitLoadId: 2,
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
      ],
    });
    renderWorkbench(receipt);

    const row = screen.getByTestId('received-line-52');
    expect(within(row).getByText('—')).toBeInTheDocument();
  });

  it('renders a Reversed indicator and muted row for a reversed received line', () => {
    const receipt = baseReceipt({
      lines: [
        {
          id: 53,
          asnLineId: null,
          itemDataId: 7,
          itemDataNumber: 'DEMO-REVERSED',
          amount: 3,
          locationId: 9,
          locationName: 'STR-03',
          unitLoadLabel: 'UL-3',
          stockUnitId: 3,
          unitLoadId: 3,
          lotNumber: null,
          bestBefore: null,
          serialNumber: null,
          packagingUnitId: null,
          lockType: null,
          note: null,
          qaHold: false,
          reversed: true,
          reversedAt: '2026-07-24T09:00:00Z',
          storageStrategyId: null,
        },
      ],
    });
    renderWorkbench(receipt);

    const row = screen.getByTestId('received-line-53');
    expect(row.className).toContain('opacity-50');
    const indicator = within(row).getByTestId('wb-line-reversed-53');
    expect(within(indicator).getByText('Reversed')).toBeInTheDocument();
  });

  it('paused receipt disables receiving and finishing and offers Resume', async () => {
    const user = userEvent.setup();
    const receipt = baseReceipt({ pausedAt: '2026-07-20T10:00:00Z' });
    renderWorkbench(receipt);

    expect(screen.getByText(/Paused since/)).toBeInTheDocument();
    expect(screen.getByText(/resume to continue receiving/)).toBeInTheDocument();
    expect(screen.getByTestId('receive-submit')).toBeDisabled();
    expect(screen.getByTestId('finish-receipt-button')).toBeDisabled();

    await user.click(screen.getByTestId('workbench-resume-button'));
    expect(resumeMutate).toHaveBeenCalledWith(5);
  });

  it('RETOUR receipt shows its badge and passes receiptType to the form', () => {
    const receipt = baseReceipt({ receiptType: GOODS_RECEIPT_TYPE.RETOUR });
    renderWorkbench(receipt);

    expect(screen.getByText('RETOUR')).toBeInTheDocument();
    // ReceiveForm defaults its lock select from the receiptType prop --
    // RETOUR -> QUALITY FAULT(103) confirms the prop was actually passed
    // through, not just the header badge.
    expect(screen.getByTestId('receive-lock-select')).toHaveValue('103');
  });
});
