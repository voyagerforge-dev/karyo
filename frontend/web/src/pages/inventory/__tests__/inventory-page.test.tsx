import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, within } from '@testing-library/react';
import type { PaginatedResponse } from '@/types/api';
import type { StockUnitResponse } from '@/types/inventory';

// Mock the data + mutation hooks. InventoryDetail's mutation hooks aren't
// exercised by these list/detail-shape tests, they just need to not blow up
// when the component renders. useFixAssignments/toReorderPointMap default to
// no fix-assignments (honest-gap reorder points) — real behavior is covered
// in inventory-rows.test.ts and use-inventory.test.ts.
vi.mock('../use-inventory', async (orig) => {
  const actual = await orig<typeof import('../use-inventory')>();
  return {
    ...actual,
    useStockUnits: vi.fn(),
    sortingStateToString: vi.fn(() => undefined),
    useAdjustStock: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
    useSetStockLock: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
    useTransferUnitLoad: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
    useChangeUnitLoadClient: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
    useSetUnitLoadCarrier: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
    useLockUnitLoad: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
    useUnlockUnitLoad: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
    useTransferToClearing: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
    useUnitLoadsByLocation: vi.fn(() => ({ data: [], isLoading: false })),
    useFixAssignments: vi.fn(() => ({ data: [] })),
  };
});

// The page heading reads the tenant from auth; InventoryDetail's write-gated
// buttons read permissions from the same context.
vi.mock('@/components/auth/auth-provider', () => ({
  useAuth: vi.fn(() => ({
    tenantCode: 'Riverside DC',
    userName: 'Mara',
    permissions: ['inventory-read', 'inventory-write'],
  })),
}));

// InventoryDetail's Move dialog reads destination locations.
vi.mock('@/pages/locations/use-locations', () => ({
  useAllLocations: vi.fn(() => ({ data: { content: [], page: { number: 0, size: 200, totalElements: 0, totalPages: 1 } } })),
}));

// InventoryDetail's per-LPN Change-owner dialog reads the client list.
vi.mock('@/features/clients/use-clients', () => ({
  useClients: vi.fn(() => ({ data: [], isLoading: false })),
}));

// sonner toast is a no-op in tests (InventoryDetail's mutation success/failure toasts).
vi.mock('sonner', () => ({ toast: vi.fn() }));

// D12: CSV export button wiring -- mocked so the click assertion doesn't hit the network.
const saveCsvMock = vi.fn();
vi.mock('@/lib/document-actions', () => ({ saveCsv: (...args: unknown[]) => saveCsvMock(...args) }));

// Real journals for SKU-001 @ B1-A09 (the auto-selected first group — see
// mockStockUnits below: SKU-001's two LPNs share locationName 'B1-A09').
vi.mock('@/features/insights/use-journals', async (orig) => {
  const actual = await orig<typeof import('@/features/insights/use-journals')>();
  return {
    ...actual,
    useJournals: vi.fn(() => ({
      data: [
        { recordType: 3, recordTypeName: 'PICKED', productNumber: 'SKU-001', amount: 4,
          fromStorageLocation: 'B1-A09', toStorageLocation: null, lotNumber: null,
          correlationId: 'DEMO-DO-00007', created: '2026-06-21T10:00:00Z' },
        { recordType: 1, recordTypeName: 'CREATED', productNumber: 'SKU-001', amount: 20,
          fromStorageLocation: null, toStorageLocation: 'B1-A09', lotNumber: null,
          correlationId: 'DEMO-UL-000', created: '2026-06-10T07:00:00Z' },
      ],
      isLoading: false,
    })),
  };
});

function su(p: Partial<StockUnitResponse> & Pick<StockUnitResponse, 'id'>): StockUnitResponse {
  return {
    id: p.id,
    itemDataId: p.itemDataId ?? 10,
    itemDataNumber: p.itemDataNumber ?? 'SKU-001',
    itemDataName: p.itemDataName ?? null,
    amount: p.amount ?? 100,
    reservedAmount: p.reservedAmount ?? 0,
    availableAmount: p.availableAmount ?? 100,
    serialNumber: null,
    lotNumber: p.lotNumber ?? null,
    bestBefore: p.bestBefore ?? null,
    state: p.state ?? 300,
    stateName: p.stateName ?? 'ON_STOCK',
    lockType: p.lockType ?? 0,
    lockTypeName: p.lockTypeName ?? 'NONE',
    strategyDate: null,
    unitLoadId: p.unitLoadId ?? 1,
    unitLoadLabel: p.unitLoadLabel ?? '',
    locationId: p.locationId ?? 1,
    locationName: p.locationName ?? 'A-01-01',
    created: '2026-03-01T00:00:00Z',
    modified: '2026-03-01T00:00:00Z',
    supplierName: p.supplierName ?? null,
    sourceAsn: p.sourceAsn ?? null,
    receivedAt: p.receivedAt ?? null,
    // Default mirrors this file's fixtures: units with an explicit LPN label
    // are LPN-tracked (aggregateStocks=false); unlabeled ones are loose.
    aggregateStocks: p.aggregateStocks ?? !p.unitLoadLabel,
    packagingUnitId: p.packagingUnitId ?? null,
  };
}

// Two LPN-tracked units of SKU-001 at the SAME location -> one group, 2 LPNs.
// One loose, fully-allocated unit of SKU-002 -> Allocated group.
// One held unit of SKU-003 -> Hold group.
const mockStockUnits: StockUnitResponse[] = [
  su({ id: 1, itemDataId: 10, itemDataNumber: 'SKU-001', amount: 200, availableAmount: 200, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-44102', lotNumber: 'LOT-7741', bestBefore: '2026-03-12' }),
  su({ id: 2, itemDataId: 10, itemDataNumber: 'SKU-001', amount: 120, availableAmount: 120, locationId: 1, locationName: 'B1-A09', unitLoadLabel: 'LPN-44103', lotNumber: 'LOT-7755' }),
  su({ id: 3, itemDataId: 11, itemDataNumber: 'SKU-002', amount: 50, reservedAmount: 50, availableAmount: 0, locationId: 2, locationName: 'A2-C07' }),
  su({ id: 4, itemDataId: 12, itemDataNumber: 'SKU-003', amount: 24, availableAmount: 24, locationId: 3, locationName: 'A9-A03', lockType: 1, lockTypeName: 'QA' }),
];

const mockPaginated: PaginatedResponse<StockUnitResponse> = {
  content: mockStockUnits,
  page: { number: 0, size: 50, totalElements: 4, totalPages: 1 },
};

import { useFixAssignments, useStockUnits } from '../use-inventory';
import { useJournals } from '@/features/insights/use-journals';
import { InventoryPage } from '../inventory-page';

beforeEach(() => {
  saveCsvMock.mockClear();
  vi.mocked(useStockUnits).mockReturnValue({
    data: mockPaginated,
    isLoading: false,
    isError: false,
    error: null,
  } as ReturnType<typeof useStockUnits>);
});

describe('InventoryPage', () => {
  it('renders the page heading with tenant', () => {
    render(<InventoryPage />);
    expect(screen.getByRole('heading', { name: 'Inventory' })).toBeInTheDocument();
    expect(screen.getByText(/Riverside DC/)).toBeInTheDocument();
  });

  it('renders the Export CSV button and wires it to the stock-units export endpoint', () => {
    render(<InventoryPage />);

    const exportBtn = screen.getByTestId('export-csv-btn');
    expect(exportBtn).toBeInTheDocument();

    fireEvent.click(exportBtn);
    expect(saveCsvMock).toHaveBeenCalledWith('/api/v1/stock-units/export.csv', 'stock-units.csv');
  });

  it('aggregates LPN-level units into item×location groups (3 list rows from 4 units)', () => {
    render(<InventoryPage />);
    // SKU-001's two LPNs collapse into one list row; 3 distinct group rows total.
    expect(screen.getByTestId('inv-row-SKU-001')).toBeInTheDocument();
    expect(screen.getByTestId('inv-row-SKU-002')).toBeInTheDocument();
    expect(screen.getByTestId('inv-row-SKU-003')).toBeInTheDocument();
    // Exactly one SKU-001 row (the two LPNs did not produce two rows).
    expect(screen.getAllByTestId(/^inv-row-/)).toHaveLength(3);
  });

  it('renders the product name as the group headline when itemDataName is present (SKU still shown)', () => {
    const named: PaginatedResponse<StockUnitResponse> = {
      content: [
        su({ id: 5, itemDataId: 20, itemDataNumber: 'SKU-004', itemDataName: 'Widget Pro', locationId: 4, locationName: 'C1-B02' }),
      ],
      page: { number: 0, size: 50, totalElements: 1, totalPages: 1 },
    };
    vi.mocked(useStockUnits).mockReturnValueOnce({
      data: named,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useStockUnits>);
    render(<InventoryPage />);
    const row = screen.getByTestId('inv-row-SKU-004');
    // Headline is the product NAME, not the SKU...
    expect(within(row).getByText('Widget Pro')).toBeInTheDocument();
    // ...and the SKU still appears in the row's secondary line.
    expect(within(row).getByText('SKU-004')).toBeInTheDocument();
  });

  it('shows an LPN-count badge for LPN-tracked groups and LOOSE otherwise', () => {
    render(<InventoryPage />);
    expect(screen.getByText('2 LPNs')).toBeInTheDocument(); // SKU-001 group
    expect(screen.getAllByText('LOOSE').length).toBeGreaterThanOrEqual(1); // loose groups
  });

  it('classifies a fully-allocated group as Allocated (amber)', () => {
    render(<InventoryPage />);
    const allocated = screen.getAllByText('Allocated');
    // status pill (list + possibly detail/KPI label). At least one carries the warning tone.
    expect(allocated.some((el) => el.className.includes('text-warning-foreground'))).toBe(true);
  });

  it('classifies a locked group as Hold (red)', () => {
    render(<InventoryPage />);
    const hold = screen.getAllByText('Hold');
    expect(hold.some((el) => el.className.includes('text-destructive'))).toBe(true);
  });

  it('renders the status filter segments and grouping toggle', () => {
    render(<InventoryPage />);
    expect(screen.getByRole('button', { name: 'All' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Available' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'Held' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'By item' })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: 'By location' })).toBeInTheDocument();
  });

  it('filters the list to held stock when Held is selected', () => {
    render(<InventoryPage />);
    fireEvent.click(screen.getByRole('button', { name: 'Held' }));
    // Only SKU-003 (locked) survives in the list.
    expect(screen.getByTestId('inv-row-SKU-003')).toBeInTheDocument();
    expect(screen.queryByTestId('inv-row-SKU-002')).not.toBeInTheDocument();
    expect(screen.queryByTestId('inv-row-SKU-001')).not.toBeInTheDocument();
  });

  it('selecting a row renders its detail workspace with qty cards and ledger', () => {
    render(<InventoryPage />);
    // SKU-001 is first by name; select it explicitly.
    fireEvent.click(screen.getByTestId('inv-row-SKU-001'));
    const detail = screen.getByTestId('inventory-detail');
    // On hand = 200 + 120 = 320 across the two LPNs (also the available qty,
    // since nothing is reserved — both cards read 320).
    expect(within(detail).getByText('On hand')).toBeInTheDocument();
    expect(within(detail).getAllByText('320').length).toBeGreaterThanOrEqual(1);
    // Stored units sub-table shows both LPNs.
    expect(within(detail).getByText('LPN-44102')).toBeInTheDocument();
    expect(within(detail).getByText('LPN-44103')).toBeInTheDocument();
    // Multi-lot -> "Mixed (2)".
    expect(within(detail).getByText('Mixed (2)')).toBeInTheDocument();
    // Movement ledger present.
    expect(within(detail).getByText('Movement ledger')).toBeInTheDocument();
  });

  it('shows the no-unit-load-breakdown note for a loose group', () => {
    render(<InventoryPage />);
    fireEvent.click(screen.getByTestId('inv-row-SKU-002'));
    const detail = screen.getByTestId('inventory-detail');
    expect(within(detail).getByText(/no unit-load breakdown/i)).toBeInTheDocument();
  });

  it('renders the Movement ledger section as an honest empty state (no fabricated rows)', () => {
    // Override the file-level (non-empty) journals mock for this one case —
    // the empty state must still render when there is no real journal history.
    vi.mocked(useJournals).mockReturnValueOnce({ data: [], isLoading: false });
    render(<InventoryPage />);
    expect(screen.getByText('Movement ledger')).toBeInTheDocument();
    expect(screen.getByText(/no movement history yet/i)).toBeInTheDocument();
    // No fabricated ledger references leak through (the mock used ASN/Cycle refs).
    expect(screen.queryByText(/Cycle CC-/)).not.toBeInTheDocument();
  });

  it('renders a real Movement ledger with a running balance', () => {
    render(<InventoryPage />);
    // ledger event labels from the real journal
    expect(screen.getByText('Movement ledger')).toBeInTheDocument();
    expect(screen.getAllByText('Pick').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('Receipt')).toBeInTheDocument();
    // no honest-empty text once rows exist
    expect(screen.queryByText(/no movement history yet/i)).not.toBeInTheDocument();
  });

  it('honest-gaps Arrived (no GR in the fixture) and does not assert a location type', () => {
    render(<InventoryPage />);
    // SKU-001 (auto-selected first group) carries a real bestBefore on its
    // first LPN, so the old derivation `fmtIso(group.lpns[0]?.bestBefore ??
    // group.earliestExpiry)` would have rendered "Mar 12" here. Arrived is
    // real (via GoodsReceiptLookup) but every fixture unit has receivedAt:
    // null (never GR-received), so it must stay a literal "—" — this
    // assertion fails if the old bestBefore-derivation is ever reintroduced.
    const received = screen.getByText('Arrived').parentElement;
    expect(within(received as HTMLElement).getByText('—')).toBeInTheDocument();
  });

  it('renders real supplier/received/source-ASN on a GR-received group', () => {
    const withReceipt: PaginatedResponse<StockUnitResponse> = {
      content: [
        su({
          id: 9, itemDataId: 30, itemDataNumber: 'SKU-009', locationId: 9, locationName: 'D1-A01',
          supplierName: 'Northwind Traders', sourceAsn: 'DEMO-ASN-00042', receivedAt: '2026-06-10T07:00:00Z',
        }),
      ],
      page: { number: 0, size: 50, totalElements: 1, totalPages: 1 },
    };
    vi.mocked(useStockUnits).mockReturnValueOnce({
      data: withReceipt,
      isLoading: false,
      isError: false,
      error: null,
    } as ReturnType<typeof useStockUnits>);
    render(<InventoryPage />);
    const detail = screen.getByTestId('inventory-detail');
    expect(within(detail).getByText('Northwind Traders')).toBeInTheDocument();
    expect(within(detail).getByText('DEMO-ASN-00042')).toBeInTheDocument();
    // "Arrived" no longer reads "—" once a real receivedAt is present.
    const received = within(detail).getByText('Arrived').parentElement;
    expect(within(received as HTMLElement).queryByText('—')).not.toBeInTheDocument();
  });

  it('the Need reorder KPI counts groups below a real fix-assignment reorder point', () => {
    // SKU-002 (id 3): itemDataId 11 @ locationId 2, available 0.
    vi.mocked(useFixAssignments).mockReturnValueOnce({
      data: [
        {
          id: 1, locationId: 2, locationName: 'A2-C07', itemDataId: 11, itemDataNumber: 'SKU-002',
          minAmount: 10, maxAmount: null, desiredAmount: null, currentStockAmount: null,
          orderIndex: 0, created: '', modified: '',
        },
      ],
    } as ReturnType<typeof useFixAssignments>);
    render(<InventoryPage />);
    const kpi = screen.getByText('Need reorder').parentElement;
    expect(within(kpi as HTMLElement).getByText('1')).toBeInTheDocument();
  });
});
