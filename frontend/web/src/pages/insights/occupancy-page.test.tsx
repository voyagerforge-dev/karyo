import { describe, it, expect, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { createElement } from 'react';

const occupancyMock = vi.fn();

vi.mock('@/features/insights/use-occupancy', () => ({
  useOccupancy: () => occupancyMock(),
}));

const { OccupancyPage } = await import('@/pages/insights/occupancy-page');

describe('OccupancyPage', () => {
  it('renders the zone card + rollup from the live model', async () => {
    occupancyMock.mockReturnValue({
      isLoading: false, isError: false, isSuccess: true,
      data: {
        zones: [
          {
            zoneId: 1, zoneName: 'Storage', occupied: 1, total: 2, pct: 0.5,
            locations: [
              { id: 31, name: 'STR-01', state: 'occupied' },
              { id: 32, name: 'STR-02', state: 'empty' },
            ],
          },
        ],
        unzoned: null,
        totals: { occupied: 1, total: 2, pct: 0.5 },
      },
    });
    render(createElement(OccupancyPage));
    await waitFor(() => expect(screen.getByText('Storage')).toBeInTheDocument());
    expect(screen.getByTestId('occupancy-page')).toBeInTheDocument();
    expect(screen.getByText('Warehouse occupancy')).toBeInTheDocument();
  });

  it('renders the slot-utilization tile with capacity data', async () => {
    occupancyMock.mockReturnValue({
      isLoading: false, isError: false, isSuccess: true,
      data: {
        zones: [
          {
            zoneId: 1, zoneName: 'Storage', occupied: 1, total: 4, pct: 0.25,
            locations: [
              { id: 31, name: 'STR-01', state: 'occupied', capacity: 4, unitLoadCount: 2 },
              { id: 32, name: 'STR-02', state: 'empty', capacity: null, unitLoadCount: 0 },
            ],
            capacitySlots: 4,
            usedSlots: 2,
          },
        ],
        unzoned: null,
        totals: {
          occupied: 1, total: 2, pct: 0.5,
          capacitySlots: 4, usedSlots: 2, locationsWithCapacity: 1, utilization: 0.5,
        },
      },
    });
    render(createElement(OccupancyPage));
    await waitFor(() => expect(screen.getByText('Slot utilization')).toBeInTheDocument());
    expect(screen.getAllByText('50%').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByText('2/4 slots · capacity set on 1/2 locations')).toBeInTheDocument();
    // per-zone slot count next to the zone's existing occupancy fraction
    expect(screen.getByText('2/4 slots')).toBeInTheDocument();
  });

  it('renders an em-dash for slot utilization when no location has a capacity set', async () => {
    occupancyMock.mockReturnValue({
      isLoading: false, isError: false, isSuccess: true,
      data: {
        zones: [
          {
            zoneId: 1, zoneName: 'Storage', occupied: 0, total: 1, pct: 0,
            locations: [{ id: 41, name: 'STR-09', state: 'empty', capacity: null, unitLoadCount: 0 }],
            capacitySlots: null,
            usedSlots: null,
          },
        ],
        unzoned: null,
        totals: {
          occupied: 0, total: 1, pct: 0,
          capacitySlots: 0, usedSlots: 0, locationsWithCapacity: 0, utilization: null,
        },
      },
    });
    render(createElement(OccupancyPage));
    await waitFor(() => expect(screen.getByText('Slot utilization')).toBeInTheDocument());
    expect(screen.getByText('—')).toBeInTheDocument();
    expect(screen.getByText('0/0 slots · capacity set on 0/1 locations')).toBeInTheDocument();
  });
});
