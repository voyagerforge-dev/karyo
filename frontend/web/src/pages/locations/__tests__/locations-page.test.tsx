import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { LocationResponse } from '@/types/location';

// Mock the hooks module before importing the component.
vi.mock('../use-locations', () => ({
  useAllLocations: vi.fn(),
  useLockLocation: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  // LocationForm (rendered in the New location dialog) needs these.
  useCreateZone: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useCreateArea: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useCreateCluster: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useCreateLocation: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useUpdateLocation: vi.fn(() => ({ mutateAsync: vi.fn(), isPending: false })),
  useAllZones: vi.fn(() => ({ data: { content: [] } })),
  useAllClusters: vi.fn(() => ({ data: { content: [] } })),
  useLocationTypes: vi.fn(() => ({ data: { content: [] }, isLoading: false })),
}));

vi.mock('../use-location-contents', () => ({
  useLocationContents: vi.fn(() => ({
    byLocation: new Map([[1, [{ sku: 'KX-9', desc: '', lot: 'LOT-1', qty: 42, status: 'Pickable' }]]]),
    usedByLocation: new Map([[1, 42]]),
    isLoading: false,
  })),
}));

// Permissions: grant layout-write so actions render enabled.
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: () => ({
    permissions: ['layout-write'],
    hasPermission: () => true,
    hasAnyPermission: () => true,
  }),
}));

vi.mock('@/features/insights/use-journals', async (orig) => {
  const actual = await orig<typeof import('@/features/insights/use-journals')>();
  return {
    ...actual,
    useJournals: vi.fn(() => ({
      data: [
        { recordType: 3, recordTypeName: 'PICKED', productNumber: 'KX-1', amount: 12,
          fromStorageLocation: 'A4-B12', toStorageLocation: null, lotNumber: null,
          correlationId: 'DEMO-DO-00042', created: '2026-06-20T10:00:00Z' },
      ],
      isLoading: false,
    })),
  };
});

import { LocationsPage } from '../locations-page';
import * as hooks from '../use-locations';
import * as journalHooks from '@/features/insights/use-journals';

const locType = {
  id: 1,
  name: 'Pick face',
  height: 60,
  width: 120,
  depth: 80,
  liftingCapacity: 250,
  created: '2026-01-01T00:00:00Z',
  modified: '2026-01-01T00:00:00Z',
};

const area = { id: 10, name: 'Area 1', usages: ['PICKING'], created: '2026-01-01T00:00:00Z', modified: '2026-01-01T00:00:00Z' };
const zoneA = { id: 1, name: 'Zone A', description: null, overflowZoneId: null, created: '2026-01-01T00:00:00Z', modified: '2026-01-01T00:00:00Z' };
const zoneB = { id: 2, name: 'Zone B', description: null, overflowZoneId: null, created: '2026-01-01T00:00:00Z', modified: '2026-01-01T00:00:00Z' };

function loc(over: Partial<LocationResponse>): LocationResponse {
  return {
    id: 0,
    name: 'BIN',
    scanCode: null,
    locationType: locType,
    area,
    zone: zoneA,
    locationCluster: null,
    allocation: 0,
    lockType: 0,
    lockTypeName: 'None',
    orderIndex: 100,
    xPos: 0,
    yPos: 0,
    zPos: 0,
    rack: null,
    field: null,
    section: null,
    capacity: null,
    temperatureZone: null,
    handlingClass: null,
    kind: null,
    lastCountedAt: null,
    isClearing: false,
    plcCode: null,
    allocationState: 0,
    created: '2026-01-01T00:00:00Z',
    modified: '2026-01-01T00:00:00Z',
    ...over,
  };
}

const mockLocations: LocationResponse[] = [
  loc({ id: 1, name: 'A4-B12', scanCode: 'A4·B12', zone: zoneA, rack: 'A4', field: 'B12', orderIndex: 412, allocation: 78 }),
  loc({ id: 2, name: 'B1-A09', scanCode: 'B1·A09', zone: zoneB, rack: 'B1', field: 'A09', orderIndex: 1090 }),
];

function listResult<T>(content: T[], isLoading = false) {
  return {
    data: { content, page: { number: 0, size: 200, totalElements: content.length, totalPages: 1 } },
    isLoading,
    isError: false,
  };
}

beforeEach(() => {
  vi.mocked(hooks.useAllLocations).mockReturnValue(
    listResult(mockLocations) as ReturnType<typeof hooks.useAllLocations>,
  );
  vi.mocked(hooks.useLockLocation).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof hooks.useLockLocation>);
});

describe('LocationsPage', () => {
  it('renders the page with location rows from real data', () => {
    render(<LocationsPage />);

    expect(screen.getByTestId('locations-page')).toBeInTheDocument();
    // Both bin codes show (list rows). The first is also the default selection (detail header).
    expect(screen.getAllByText('A4·B12').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('B1·A09')).toBeInTheDocument();
  });

  it('auto-selects the first location and shows detail workspace sections', () => {
    render(<LocationsPage />);

    // Detail workspace headings.
    expect(screen.getByText('Constraints')).toBeInTheDocument();
    expect(screen.getByText('Stored here')).toBeInTheDocument();
    expect(screen.getByText('Recent movements')).toBeInTheDocument();
  });

  it('selecting a different location updates the detail header', async () => {
    const user = userEvent.setup();
    render(<LocationsPage />);

    await user.click(screen.getByText('B1·A09'));

    // The detail header (mono h1) now shows the selected code at least twice
    // (list row + detail header).
    expect(screen.getAllByText('B1·A09').length).toBeGreaterThanOrEqual(2);
  });

  it('search filters the location list by code', async () => {
    const user = userEvent.setup();
    render(<LocationsPage />);

    await user.type(screen.getByPlaceholderText(/search bin or zone/i), 'A09');

    // B1·A09 remains (list row + auto-selected detail header).
    expect(screen.getAllByText('B1·A09').length).toBeGreaterThanOrEqual(1);
    // A4·B12 is filtered out of the list and detail entirely.
    expect(screen.queryByText('A4·B12')).not.toBeInTheDocument();
  });

  it('New location button opens the create dialog', async () => {
    const user = userEvent.setup();
    render(<LocationsPage />);

    await user.click(screen.getByRole('button', { name: /new location/i }));

    // Dialog title + the form's Save button.
    expect(screen.getAllByText(/new location/i).length).toBeGreaterThanOrEqual(2);
    expect(screen.getByRole('button', { name: /save/i })).toBeInTheDocument();
  });

  it('renders real Stored-here contents for the selected location', () => {
    render(<LocationsPage />);
    // Location id 1 (A4·B12) auto-selected; its real stock unit shows.
    expect(screen.getByText('KX-9')).toBeInTheDocument();
    // '42' is both the content row's qty and the occupancy ring's "used" stat
    // (a single-row location has qty === used), so it renders twice.
    expect(screen.getAllByText('42').length).toBeGreaterThanOrEqual(1);
  });

  it('shows an empty state when no locations match the filter', async () => {
    const user = userEvent.setup();
    render(<LocationsPage />);

    await user.type(screen.getByPlaceholderText(/search bin or zone/i), 'ZZZZ');

    expect(screen.getByText(/no locations match your filters/i)).toBeInTheDocument();
  });

  it('shows the real allocation-derived occupancy and honest constraint gaps', () => {
    render(<LocationsPage />);
    // 78% appears in the list row and the detail ring.
    expect(screen.getAllByText('78%').length).toBeGreaterThanOrEqual(1);
    // Un-modeled constraints render an em dash, not fabricated values.
    expect(screen.getAllByText('—').length).toBeGreaterThanOrEqual(1);
  });

  it('shows an honest empty state for recent movements', () => {
    vi.mocked(journalHooks.useJournals).mockReturnValueOnce({ data: [], isLoading: false });
    render(<LocationsPage />);
    expect(screen.getByText(/no recent movements yet/i)).toBeInTheDocument();
  });

  it('renders real Recent movements from the journal for the selected location', () => {
    render(<LocationsPage />);
    expect(screen.getByText('Recent movements')).toBeInTheDocument();
    expect(screen.getByText(/Pick 12/)).toBeInTheDocument();
    expect(screen.getByText(/DEMO-DO-00042/)).toBeInTheDocument();
    expect(screen.queryByText(/no recent movements yet/i)).not.toBeInTheDocument();
  });
});

describe('LocationsPage — Task 10 edit mode', () => {
  it('Edit button swaps the detail pane into the edit form', async () => {
    const user = userEvent.setup();
    render(<LocationsPage />);

    // Detail workspace is showing (view mode).
    expect(screen.getByText('Constraints')).toBeInTheDocument();

    await user.click(screen.getByTestId('location-edit-btn'));

    // Detail view sections are gone; the form is showing instead.
    expect(screen.queryByText('Constraints')).not.toBeInTheDocument();
    expect(screen.getByText(/edit a4·b12/i)).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /save/i })).toBeInTheDocument();
  });

  it('Cancel from the edit form returns to the view detail', async () => {
    const user = userEvent.setup();
    render(<LocationsPage />);

    await user.click(screen.getByTestId('location-edit-btn'));
    expect(screen.getByRole('button', { name: /cancel/i })).toBeInTheDocument();

    await user.click(screen.getByRole('button', { name: /cancel/i }));

    expect(screen.getByText('Constraints')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^save$/i })).not.toBeInTheDocument();
  });

  it('Save from the edit form calls updateLocation and returns to the view detail', async () => {
    const user = userEvent.setup();
    const mutateAsync = vi.fn().mockResolvedValue({});
    vi.mocked(hooks.useUpdateLocation).mockReturnValue({
      mutateAsync,
      isPending: false,
    } as unknown as ReturnType<typeof hooks.useUpdateLocation>);

    render(<LocationsPage />);

    await user.click(screen.getByTestId('location-edit-btn'));
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(mutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ id: 1 }),
    );
    // Back to the view detail.
    expect(await screen.findByText('Constraints')).toBeInTheDocument();
  });
});
