import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { LocationView } from '../location-derive';
import type { ContentRow } from '../use-location-contents';

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

vi.mock('@/features/insights/use-journals', async (orig) => {
  const actual = await orig<typeof import('@/features/insights/use-journals')>();
  return { ...actual, useJournals: vi.fn(() => ({ data: [], isLoading: false })) };
});

const saveZpl = vi.fn();
vi.mock('@/lib/document-actions', () => ({
  saveZpl: (u: string, f: string) => saveZpl(u, f),
}));

import { LocationDetail } from '../location-detail';

function view(overrides: Partial<LocationView> = {}): LocationView {
  return {
    id: 12,
    name: 'B1-A09',
    code: 'B1-A09',
    zoneLabel: 'Zone B',
    zoneShort: 'B1',
    bay: 'A09',
    typeName: 'Pallet rack',
    kind: 'Reserve',
    status: 'Active',
    lockTypeName: 'UNDEFINED',
    lockType: 0,
    pickSequence: 1,
    occPct: 40,
    dimensions: '—',
    maxWeight: '—',
    storageClass: '—',
    temperature: '—',
    replenRule: '—',
    lastCounted: '—',
    capacity: null,
    plcCode: '—',
    excludedFromPutaway: false,
    isClearing: false,
    note: null,
    ...overrides,
  };
}

const contents: ContentRow[] = [];

function renderDetail(v: LocationView = view(), canWrite = true, onEdit = vi.fn()) {
  return render(
    <LocationDetail view={v} contents={contents} used={0} canWrite={canWrite} onBlock={vi.fn()} onEdit={onEdit} />,
  );
}

beforeEach(() => {
  saveZpl.mockClear();
});

describe('LocationDetail — Task 8 Label (ZPL) document button', () => {
  it('is always visible, even without write permission (read-only)', () => {
    renderDetail(view(), false);
    expect(screen.getByTestId('doc-label-btn')).toBeInTheDocument();
    expect(screen.getByTestId('doc-label-btn')).not.toBeDisabled();
  });

  it('requests the ZPL label for this location id, filename from the location name', async () => {
    const user = userEvent.setup();
    renderDetail(view({ id: 12, name: 'B1-A09' }));
    await user.click(screen.getByTestId('doc-label-btn'));
    expect(saveZpl).toHaveBeenCalledWith('/api/v1/locations/12/label.zpl', 'location-B1-A09.zpl');
  });
});

describe('LocationDetail — L3 plcCode + allocationState (locations-layout sprint)', () => {
  it('shows the "PLC code" constraint label, honest "—" when unset', () => {
    renderDetail(view({ plcCode: '—' }));
    expect(screen.getByText('PLC code')).toBeInTheDocument();
  });

  it('renders the real PLC code when the backend has one', () => {
    renderDetail(view({ plcCode: 'PLC-42' }));
    expect(screen.getByText('PLC-42')).toBeInTheDocument();
  });

  it('hides the "Excluded from putaway" badge when allocationState is 0', () => {
    renderDetail(view({ excludedFromPutaway: false }));
    expect(screen.queryByTestId('excluded-from-putaway-badge')).not.toBeInTheDocument();
  });

  it('shows the "Excluded from putaway" badge when allocationState is non-zero', () => {
    renderDetail(view({ excludedFromPutaway: true }));
    expect(screen.getByTestId('excluded-from-putaway-badge')).toBeInTheDocument();
    expect(screen.getByTestId('excluded-from-putaway-badge')).toHaveTextContent('Excluded from putaway');
  });
});

describe('LocationDetail — Task 10 Clearing badge + Edit action', () => {
  it('hides the "Clearing" badge when isClearing is false', () => {
    renderDetail(view({ isClearing: false }));
    expect(screen.queryByTestId('clearing-badge')).not.toBeInTheDocument();
  });

  it('shows the "Clearing" badge when isClearing is true', () => {
    renderDetail(view({ isClearing: true }));
    expect(screen.getByTestId('clearing-badge')).toHaveTextContent('Clearing');
  });

  it('calls onEdit when the Edit button is clicked', async () => {
    const user = userEvent.setup();
    const onEdit = vi.fn();
    renderDetail(view(), true, onEdit);
    await user.click(screen.getByTestId('location-edit-btn'));
    expect(onEdit).toHaveBeenCalledTimes(1);
  });

  it('disables the Edit button without write permission', () => {
    renderDetail(view(), false);
    expect(screen.getByTestId('location-edit-btn')).toBeDisabled();
  });
});
