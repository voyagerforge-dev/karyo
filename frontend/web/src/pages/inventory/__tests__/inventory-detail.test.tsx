import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ItemLocationGroup } from '../inventory-rows';
import type { ClientResponse } from '@/types/client';

// Radix Select (client picker in the Change-owner dialog) needs these two DOM
// APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

// sonner toast is a no-op in tests.
vi.mock('sonner', () => ({ toast: Object.assign(vi.fn(), { success: vi.fn(), error: vi.fn() }) }));

const viewPdf = vi.fn();
const saveZpl = vi.fn();
vi.mock('@/lib/document-actions', () => ({
  viewPdf: (u: string) => viewPdf(u),
  saveZpl: (u: string, f: string) => saveZpl(u, f),
}));

// No real journal fetches — keep the ledger section quiet/empty for these tests.
vi.mock('@/features/insights/use-journals', async (orig) => {
  const actual = await orig<typeof import('@/features/insights/use-journals')>();
  return { ...actual, useJournals: vi.fn(() => ({ data: [], isLoading: false })) };
});

const adjustMutateAsync = vi.fn().mockResolvedValue({});
const lockMutateAsync = vi.fn().mockResolvedValue({});
const transferMutateAsync = vi.fn().mockResolvedValue({});
const lockUnitLoadMutateAsync = vi.fn().mockResolvedValue({});
const unlockUnitLoadMutateAsync = vi.fn().mockResolvedValue({});
const transferToClearingMutateAsync = vi.fn().mockResolvedValue({});
// Mutable per-test fixture for the per-group `GET /unit-loads?locationId=`
// fetch (A2 pallet-lock source) — set in each test via `mockUnitLoads`.
let mockUnitLoads: Array<{ id: number; lockType: number; lockTypeName: string; isCarrier?: boolean }> = [];

vi.mock('../use-inventory', async (orig) => {
  // Keep the real `moveUnitLoads` (a pure helper, not a hook) and the real
  // `useChangeUnitLoadClient`/`useSetUnitLoadCarrier` (they post through the
  // mocked `@/lib/api-client` below, so assertions can check the exact
  // request) — only the pre-existing mutation hooks need stubbing here.
  const actual = await orig<typeof import('../use-inventory')>();
  return {
    ...actual,
    useAdjustStock: vi.fn(() => ({ mutateAsync: adjustMutateAsync, isPending: false })),
    useSetStockLock: vi.fn(() => ({ mutateAsync: lockMutateAsync, isPending: false })),
    useTransferUnitLoad: vi.fn(() => ({ mutateAsync: transferMutateAsync, isPending: false })),
    useUnitLoadsByLocation: vi.fn(() => ({ data: mockUnitLoads, isLoading: false })),
    useLockUnitLoad: vi.fn(() => ({ mutateAsync: lockUnitLoadMutateAsync, isPending: false })),
    useUnlockUnitLoad: vi.fn(() => ({ mutateAsync: unlockUnitLoadMutateAsync, isPending: false })),
    useTransferToClearing: vi.fn(() => ({
      mutateAsync: transferToClearingMutateAsync,
      isPending: false,
    })),
  };
});

vi.mock('@/pages/locations/use-locations', () => ({
  useAllLocations: vi.fn(() => ({
    data: {
      content: [
        { id: 1, name: 'B1-A09' }, // the group's own location — must be excluded
        { id: 2, name: 'A2-C07' },
      ],
      page: { number: 0, size: 200, totalElements: 2, totalPages: 1 },
    },
  })),
}));

let mockCanWrite = true;
let mockCanExtinguish = true;
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    hasPermission: (p: string) => {
      if (p === 'inventory-write') return mockCanWrite;
      if (p === 'fulfillment-write') return mockCanExtinguish;
      return false;
    },
    hasAnyPermission: () => true,
  })),
}));

const extinguishMutateAsync = vi.fn().mockResolvedValue({});
vi.mock('@/features/picking/use-pick-orders', () => ({
  useExtinguishStock: vi.fn(() => ({ mutateAsync: extinguishMutateAsync, isPending: false })),
}));

const CLIENTS: ClientResponse[] = [
  { id: 0, name: 'SYS', number: 'CL-000', code: 'SYS', email: '', phone: '', fax: '', state: 'ACTIVE', isSystemClient: true },
  { id: 1, name: 'ACME', number: 'CL-001', code: 'ACME', email: '', phone: '', fax: '', state: 'ACTIVE', isSystemClient: false },
  { id: 2, name: 'GLOBEX', number: 'CL-002', code: 'GLBX', email: '', phone: '', fax: '', state: 'ACTIVE', isSystemClient: false },
  // Retired goods-owner — must not be offered as a change-owner target.
  { id: 3, name: 'INITECH', number: 'CL-003', code: 'INIT', email: '', phone: '', fax: '', state: 'INACTIVE', isSystemClient: false },
];
vi.mock('@/features/clients/use-clients', () => ({
  useClients: vi.fn(() => ({ data: CLIENTS, isLoading: false })),
}));

const apiPost = vi.fn().mockResolvedValue({});
vi.mock('@/lib/api-client', () => ({ api: { get: vi.fn(), post: (...args: unknown[]) => apiPost(...args) } }));

import { InventoryDetail } from '../inventory-detail';

/** Renders InventoryDetail under a fresh QueryClient — the real
 *  useChangeUnitLoadClient/useSetUnitLoadCarrier hooks (unlike the other
 *  mutation hooks above) are not stubbed, so they need a real provider. */
function renderDetail(group: ItemLocationGroup = baseGroup()) {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={qc}>
      <InventoryDetail group={group} />
    </QueryClientProvider>,
  );
}

function baseGroup(overrides: Partial<ItemLocationGroup> = {}): ItemLocationGroup {
  return {
    key: '10@1',
    itemDataId: 10,
    locationId: 1,
    name: 'Widget Pro',
    sku: 'SKU-001',
    location: 'B1-A09',
    locType: '—',
    lpnTracked: false,
    onHand: 50,
    reserved: 0,
    available: 50,
    lots: [],
    lpns: [],
    stockUnitIds: [7],
    unitLoadIds: [700],
    earliestExpiry: null,
    earliestDaysLeft: null,
    held: false,
    status: { status: 'Available', tone: 'lime' },
    supplierName: null,
    sourceAsn: null,
    receivedAt: null,
    reorderPoint: null,
    ...overrides,
  };
}

beforeEach(() => {
  vi.clearAllMocks();
  viewPdf.mockClear();
  saveZpl.mockClear();
  adjustMutateAsync.mockResolvedValue({});
  lockMutateAsync.mockResolvedValue({});
  transferMutateAsync.mockResolvedValue({});
  lockUnitLoadMutateAsync.mockResolvedValue({});
  unlockUnitLoadMutateAsync.mockResolvedValue({});
  transferToClearingMutateAsync.mockResolvedValue({});
  mockUnitLoads = [];
  mockCanWrite = true;
  mockCanExtinguish = true;
  extinguishMutateAsync.mockClear();
  extinguishMutateAsync.mockResolvedValue({});
});

describe('InventoryDetail — Adjust dialog', () => {
  it('adjusts the sole stock unit when the group has exactly one', async () => {
    renderDetail(baseGroup());
    fireEvent.click(screen.getByRole('button', { name: 'Adjust' }));

    const amountInput = screen.getByLabelText('New on-hand quantity');
    fireEvent.change(amountInput, { target: { value: '80' } });
    fireEvent.click(screen.getByRole('button', { name: 'Save' }));

    await waitFor(() =>
      expect(adjustMutateAsync).toHaveBeenCalledWith({ id: 7, newAmount: 80 }),
    );
  });

  it('requires picking a unit before adjusting a multi-unit group', () => {
    const group = baseGroup({
      stockUnitIds: [7, 8],
      unitLoadIds: [700, 701],
      lpnTracked: true,
      lpns: [
        { id: 7, unitLoadId: 700, lpn: 'LPN-A', lot: null, bestBefore: null, daysLeft: null, qty: 30 },
        { id: 8, unitLoadId: 701, lpn: 'LPN-B', lot: null, bestBefore: null, daysLeft: null, qty: 20 },
      ],
    });
    renderDetail(group);
    fireEvent.click(screen.getByRole('button', { name: 'Adjust' }));

    // No unit pre-selected -> Save is honestly disabled rather than guessing a split.
    expect(screen.getByRole('button', { name: 'Save' })).toBeDisabled();
  });
});

describe('InventoryDetail — Hold / Release dialog', () => {
  it('shows "Hold" for an available group and posts lockType 103 with the reason', async () => {
    renderDetail(baseGroup());
    const holdButton = screen.getByRole('button', { name: 'Hold' });
    expect(holdButton).toBeInTheDocument();
    fireEvent.click(holdButton);

    fireEvent.change(screen.getByLabelText('Reason (optional)'), {
      target: { value: 'suspected damage on inbound' },
    });
    fireEvent.click(screen.getByRole('button', { name: 'Place hold' }));

    await waitFor(() =>
      expect(lockMutateAsync).toHaveBeenCalledWith({
        id: 7,
        lockType: 103,
        reason: 'suspected damage on inbound',
      }),
    );
  });

  it('shows "Release" for a held group and posts lockType 0 for every stock unit', async () => {
    const group = baseGroup({
      stockUnitIds: [7, 8],
      status: { status: 'Hold', tone: 'red' },
    });
    renderDetail(group);
    const releaseButton = screen.getByRole('button', { name: 'Release' });
    expect(releaseButton).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: 'Hold' })).not.toBeInTheDocument();
    fireEvent.click(releaseButton);

    // Two "Release" buttons now exist: the header trigger and the dialog's
    // confirm action; the confirm button is the one rendered last.
    const releaseButtons = screen.getAllByRole('button', { name: 'Release' });
    fireEvent.click(releaseButtons[releaseButtons.length - 1]);

    await waitFor(() => {
      expect(lockMutateAsync).toHaveBeenCalledWith({ id: 7, lockType: 0, reason: undefined });
      expect(lockMutateAsync).toHaveBeenCalledWith({ id: 8, lockType: 0, reason: undefined });
    });
  });
});

describe('InventoryDetail — Move dialog', () => {
  it('opens describing every unit load the group will relocate', () => {
    const group = baseGroup({ unitLoadIds: [700, 701] });
    renderDetail(group);
    fireEvent.click(screen.getByRole('button', { name: 'Move' }));

    expect(screen.getByText(/Move stock — Widget Pro/)).toBeInTheDocument();
    expect(screen.getByText(/Moves all 2 unit loads at B1-A09/)).toBeInTheDocument();
  });

  it('disables the confirm button until a destination is chosen', () => {
    renderDetail(baseGroup());
    fireEvent.click(screen.getByRole('button', { name: 'Move' }));

    // Two "Move" buttons now exist: the header trigger and the dialog's
    // confirm action; the confirm button is the one rendered last and is
    // honestly disabled until a destination is picked.
    const moveButtons = screen.getAllByRole('button', { name: 'Move' });
    const confirmButton = moveButtons[moveButtons.length - 1];
    expect(confirmButton).toBeDisabled();
  });
});

describe('InventoryDetail — write-permission gating', () => {
  it('disables Adjust/Move/Hold when the user lacks inventory-write', () => {
    mockCanWrite = false;
    renderDetail(baseGroup());
    expect(screen.getByRole('button', { name: 'Adjust' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Move' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Hold' })).toBeDisabled();
  });
});

describe('InventoryDetail — supplier / received / reorder point', () => {
  it('honest-gaps supplier, received and reorder point to "—" when null', () => {
    renderDetail(baseGroup());
    expect(within(screen.getByText('Supplier').parentElement as HTMLElement).getByText('—')).toBeInTheDocument();
    expect(within(screen.getByText('Arrived').parentElement as HTMLElement).getByText('—')).toBeInTheDocument();
    expect(within(screen.getByText('Source ASN').parentElement as HTMLElement).getByText('—')).toBeInTheDocument();
    expect(within(screen.getByText('Reorder point').parentElement as HTMLElement).getByText('—')).toBeInTheDocument();
  });

  it('renders real supplier, received, source ASN and reorder point when present', () => {
    const group = baseGroup({
      supplierName: 'Northwind Traders',
      sourceAsn: 'DEMO-ASN-00042',
      receivedAt: '2026-06-10T07:00:00Z',
      reorderPoint: 25,
    });
    renderDetail(group);
    expect(within(screen.getByText('Supplier').parentElement as HTMLElement).getByText('Northwind Traders')).toBeInTheDocument();
    expect(within(screen.getByText('Source ASN').parentElement as HTMLElement).getByText('DEMO-ASN-00042')).toBeInTheDocument();
    expect(within(screen.getByText('Reorder point').parentElement as HTMLElement).getByText('25')).toBeInTheDocument();
    // Arrived formats the ISO timestamp as a short date, not "—".
    expect(within(screen.getByText('Arrived').parentElement as HTMLElement).queryByText('—')).not.toBeInTheDocument();
  });
});

/** A group with one LPN row on unit load 41 — the "Stored units" table only
 *  renders per-row actions when lpnTracked with at least one lpn. */
function lpnGroup(overrides: Partial<ItemLocationGroup> = {}): ItemLocationGroup {
  return baseGroup({
    lpnTracked: true,
    stockUnitIds: [7],
    unitLoadIds: [41],
    lpns: [
      { id: 7, unitLoadId: 41, lpn: 'LPN-A', lot: null, bestBefore: null, daysLeft: null, qty: 50 },
    ],
    ...overrides,
  });
}

describe('InventoryDetail — unit-load actions (change owner / carrier)', () => {
  it('offers Change owner and Mark as carrier on an LPN row for writers (not yet a carrier)', async () => {
    mockUnitLoads = [{ id: 41, lockType: 0, lockTypeName: 'UNDEFINED', isCarrier: false }];
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    expect(screen.getByText('Change owner…')).toBeInTheDocument();
    expect(screen.getByText('Mark as carrier')).toBeInTheDocument();
    expect(screen.queryByText('Unmark carrier')).not.toBeInTheDocument();
  });

  it('offers only Unmark carrier when the unit load is already a carrier', async () => {
    mockUnitLoads = [{ id: 41, lockType: 0, lockTypeName: 'UNDEFINED', isCarrier: true }];
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    expect(screen.getByText('Unmark carrier')).toBeInTheDocument();
    expect(screen.queryByText('Mark as carrier')).not.toBeInTheDocument();
  });

  it('submits a change-owner request with the selected client', async () => {
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    await userEvent.click(screen.getByText('Change owner…'));

    // Select 'GLOBEX' in the dialog (clients mocked from useClients).
    await userEvent.click(screen.getByRole('combobox'));
    await userEvent.click(await screen.findByText(/GLOBEX/i));
    fireEvent.change(screen.getByLabelText(/reason/i), {
      target: { value: 'Reassigned to 3PL customer' },
    });
    await userEvent.click(screen.getByRole('button', { name: 'Change owner' }));

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith(
        '/api/v1/unit-loads/41/change-client',
        expect.objectContaining({
          targetClientId: 2,
          activityCode: 'Reassigned to 3PL customer',
        }),
      ),
    );
  });

  it('does not offer an INACTIVE client as a change-owner target', async () => {
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    await userEvent.click(screen.getByText('Change owner…'));

    await userEvent.click(screen.getByRole('combobox'));
    // ACME (a real ACTIVE non-system client) is offered...
    expect(await screen.findByText(/ACME/i)).toBeInTheDocument();
    // ...but the retired (INACTIVE) INITECH is not — a retired goods-owner
    // must not acquire new stock via the UI.
    expect(screen.queryByText(/INITECH/i)).not.toBeInTheDocument();
  });

  it('posts a carrier flag when the carrier toggle items are used', async () => {
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    await userEvent.click(screen.getByText('Mark as carrier'));

    await waitFor(() =>
      expect(apiPost).toHaveBeenCalledWith('/api/v1/unit-loads/41/carrier', { isCarrier: true }),
    );
  });

  it('still offers the actions dropdown without inventory-write (doc actions are read-only)', async () => {
    mockCanWrite = false;
    renderDetail(lpnGroup());
    expect(screen.getByRole('button', { name: /unit load actions/i })).toBeInTheDocument();
    await userEvent.click(screen.getByRole('button', { name: /unit load actions/i }));
    // Write-only items are hidden...
    expect(screen.queryByText('Change owner…')).not.toBeInTheDocument();
    expect(screen.queryByText('Mark as carrier')).not.toBeInTheDocument();
    // ...but the read-only document actions remain.
    expect(screen.getByText('Content list')).toBeInTheDocument();
    expect(screen.getByText('Label (ZPL)')).toBeInTheDocument();
  });
});

describe('InventoryDetail — Task 8 per-LPN document actions', () => {
  it('offers Content list and Label (ZPL) regardless of write permission', async () => {
    mockCanWrite = true;
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    expect(screen.getByTestId('doc-content-list-btn-7')).toBeInTheDocument();
    expect(screen.getByTestId('doc-label-btn-7')).toBeInTheDocument();
  });

  it('requests the content-list PDF for the row unit load id when clicked', async () => {
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    await userEvent.click(screen.getByTestId('doc-content-list-btn-7'));
    expect(viewPdf).toHaveBeenCalledWith('/api/v1/unit-loads/41/content-list.pdf');
  });

  it('downloads the ZPL label named after the LPN for the row unit load id when clicked', async () => {
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    await userEvent.click(screen.getByTestId('doc-label-btn-7'));
    expect(saveZpl).toHaveBeenCalledWith('/api/v1/unit-loads/41/label.zpl', 'ul-LPN-A.zpl');
  });
});

describe('InventoryDetail — unit-load lock/clearing (A2)', () => {
  it('renders the lock pill when the unit load at this location is locked', () => {
    mockUnitLoads = [{ id: 41, lockType: 1, lockTypeName: 'GENERAL' }];
    renderDetail(lpnGroup());
    expect(screen.getByTestId('ul-lock-pill')).toBeInTheDocument();
    expect(screen.getByText('GENERAL')).toBeInTheDocument();
  });

  it('shows no pill and offers Lock (not Unlock) when the unit load is unlocked', async () => {
    mockUnitLoads = [{ id: 41, lockType: 0, lockTypeName: 'UNDEFINED' }];
    renderDetail(lpnGroup());
    expect(screen.queryByTestId('ul-lock-pill')).not.toBeInTheDocument();

    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    expect(screen.getByTestId('ul-lock-btn')).toBeInTheDocument();
    expect(screen.queryByTestId('ul-unlock-btn')).not.toBeInTheDocument();
  });

  it('Lock action opens a confirm dialog and calls useLockUnitLoad on confirm', async () => {
    mockUnitLoads = [{ id: 41, lockType: 0, lockTypeName: 'UNDEFINED' }];
    renderDetail(lpnGroup());

    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    await userEvent.click(screen.getByTestId('ul-lock-btn'));
    expect(screen.getByText('Lock unit load?')).toBeInTheDocument();

    const dialog = screen.getByRole('alertdialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Lock' }));

    await waitFor(() => expect(lockUnitLoadMutateAsync).toHaveBeenCalledWith({ id: 41 }));
  });

  it('hides Lock and offers Unlock (direct, no dialog) when the unit load is locked', async () => {
    mockUnitLoads = [{ id: 41, lockType: 1, lockTypeName: 'GENERAL' }];
    renderDetail(lpnGroup());

    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    expect(screen.queryByTestId('ul-lock-btn')).not.toBeInTheDocument();
    await userEvent.click(screen.getByTestId('ul-unlock-btn'));

    await waitFor(() => expect(unlockUnitLoadMutateAsync).toHaveBeenCalledWith(41));
  });

  it('Send to clearing opens a confirm dialog naming the destination behavior', async () => {
    mockUnitLoads = [{ id: 41, lockType: 0, lockTypeName: 'UNDEFINED' }];
    renderDetail(lpnGroup());

    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    await userEvent.click(screen.getByTestId('ul-clearing-btn'));
    expect(screen.getByText('Send to clearing?')).toBeInTheDocument();

    const dialog = screen.getByRole('alertdialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Send to clearing' }));

    await waitFor(() => expect(transferToClearingMutateAsync).toHaveBeenCalledWith({ id: 41 }));
  });
});

describe('InventoryDetail — Clear stock / extinguish (row 20)', () => {
  it('offers "Clear stock (extinguish)…" when fulfillment-write is held', async () => {
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    expect(screen.getByTestId('ul-extinguish-btn')).toBeInTheDocument();
  });

  it('hides "Clear stock (extinguish)…" without fulfillment-write, even with inventory-write', async () => {
    mockCanExtinguish = false;
    renderDetail(lpnGroup());
    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    expect(screen.queryByTestId('ul-extinguish-btn')).not.toBeInTheDocument();
  });

  it('opens a confirm dialog and calls useExtinguishStock with the row unit load id', async () => {
    renderDetail(lpnGroup());

    await userEvent.click(screen.getAllByRole('button', { name: /unit load actions/i })[0]);
    await userEvent.click(screen.getByTestId('ul-extinguish-btn'));
    expect(screen.getByText('Clear stock?')).toBeInTheDocument();

    const dialog = screen.getByRole('alertdialog');
    await userEvent.click(within(dialog).getByRole('button', { name: 'Clear stock' }));

    await waitFor(() => expect(extinguishMutateAsync).toHaveBeenCalledWith({ unitLoadId: 41 }));
  });
});
