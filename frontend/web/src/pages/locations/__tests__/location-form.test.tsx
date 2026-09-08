import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { LocationResponse } from '@/types/location';
import { ApiError } from '@/lib/api-client';

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

// Mock use-locations hooks
vi.mock('../use-locations', () => ({
  useZones: vi.fn(),
  useAreas: vi.fn(),
  useClusters: vi.fn(),
  useLocations: vi.fn(),
  useCreateZone: vi.fn(() => ({ mutateAsync: vi.fn().mockResolvedValue({}), isPending: false })),
  useCreateArea: vi.fn(() => ({ mutateAsync: vi.fn().mockResolvedValue({}), isPending: false })),
  useCreateCluster: vi.fn(() => ({ mutateAsync: vi.fn().mockResolvedValue({}), isPending: false })),
  useCreateLocation: vi.fn(() => ({ mutateAsync: vi.fn().mockResolvedValue({}), isPending: false })),
  useUpdateLocation: vi.fn(() => ({ mutateAsync: vi.fn().mockResolvedValue({}), isPending: false })),
  useLockLocation: vi.fn(() => ({ mutateAsync: vi.fn().mockResolvedValue({}), isPending: false })),
  useAllZones: vi.fn(() => ({ data: { content: [] } })),
  useAllClusters: vi.fn(() => ({ data: { content: [] } })),
  useLocationTypes: vi.fn(() => ({ data: { content: [] }, isLoading: false })),
}));

import { LocationForm } from '../location-form';
import * as hooks from '../use-locations';

const onSave = vi.fn();
const onCancel = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
});

describe('LocationForm', () => {
  it('zone form shows name and description fields only', () => {
    render(
      <LocationForm level="zones" entity={null} onSave={onSave} onCancel={onCancel} />,
    );

    expect(screen.getByLabelText(/name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/description/i)).toBeInTheDocument();
    // Should NOT show location-specific fields
    expect(screen.queryByLabelText(/scan code/i)).not.toBeInTheDocument();
    expect(screen.queryByLabelText(/location type/i)).not.toBeInTheDocument();
  });

  it('location form shows name, scanCode, locationTypeId, orderIndex fields', () => {
    render(
      <LocationForm level="locations" entity={null} parentId={10} onSave={onSave} onCancel={onCancel} />,
    );

    expect(screen.getByLabelText(/name/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/scan code/i)).toBeInTheDocument();
    // Location Type is now a Select dropdown (combobox), not an Input
    expect(screen.getByText('Location Type')).toBeInTheDocument();
    expect(screen.getAllByRole('combobox').length).toBeGreaterThanOrEqual(1);
    expect(screen.getByLabelText(/order index/i)).toBeInTheDocument();
  });

  it('validates required field (name) on submit -- shows error', async () => {
    const user = userEvent.setup();
    render(
      <LocationForm level="zones" entity={null} onSave={onSave} onCancel={onCancel} />,
    );

    // Click Save without filling name
    const saveButton = screen.getByRole('button', { name: /save/i });
    await user.click(saveButton);

    // Error message should appear
    expect(screen.getByText(/name is required/i)).toBeInTheDocument();
    // onSave should NOT have been called
    expect(onSave).not.toHaveBeenCalled();
  });

  it('edit mode pre-fills form with entity data', () => {
    const existingZone = {
      id: 1,
      name: 'Warehouse A',
      description: 'Main warehouse zone',
      overflowZoneId: null,
      created: '2026-01-01T00:00:00Z',
      modified: '2026-01-01T00:00:00Z',
    };

    render(
      <LocationForm level="zones" entity={existingZone} onSave={onSave} onCancel={onCancel} />,
    );

    expect(screen.getByLabelText(/name/i)).toHaveValue('Warehouse A');
    expect(screen.getByLabelText(/description/i)).toHaveValue('Main warehouse zone');
  });

  it('form submits correct data structure for create zone', async () => {
    const user = userEvent.setup();
    const mockMutate = vi.fn().mockResolvedValue({});
    vi.mocked(hooks.useCreateZone).mockReturnValue({
      mutateAsync: mockMutate,
      isPending: false,
    } as unknown as ReturnType<typeof hooks.useCreateZone>);

    render(
      <LocationForm level="zones" entity={null} onSave={onSave} onCancel={onCancel} />,
    );

    // Fill in the name
    await user.type(screen.getByLabelText(/name/i), 'New Zone');
    await user.type(screen.getByLabelText(/description/i), 'A test zone');

    // Submit
    const saveButton = screen.getByRole('button', { name: /save/i });
    await user.click(saveButton);

    expect(mockMutate).toHaveBeenCalledWith({
      name: 'New Zone',
      description: 'A test zone',
    });
  });
});

const existingLocation: LocationResponse = {
  id: 5,
  name: 'A1-B2',
  scanCode: 'A1-B2',
  locationType: {
    id: 1, name: 'Pallet Rack', height: null, width: null, depth: null,
    liftingCapacity: null, created: '', modified: '',
  },
  area: { id: 10, name: 'Area 1', usages: [], created: '', modified: '' },
  zone: null,
  locationCluster: null,
  allocation: 0,
  lockType: 0,
  lockTypeName: 'None',
  orderIndex: 5,
  xPos: 0, yPos: 0, zPos: 0,
  rack: null, field: null, section: null,
  created: '', modified: '',
  capacity: 10,
  temperatureZone: 'CHILLED',
  handlingClass: 'HAZMAT',
  kind: 'RESERVE',
  lastCountedAt: null,
  isClearing: false,
  plcCode: 'PLC-1',
  allocationState: 0,
};

describe('LocationForm — Task 10: V309/V310 metadata + isClearing edit round-trip', () => {
  it('edit mode pre-fills capacity, PLC code, and the clearing/exclusion switches', () => {
    render(
      <LocationForm level="locations" entity={existingLocation} parentId={10} onSave={onSave} onCancel={onCancel} />,
    );

    expect(screen.getByLabelText(/capacity/i)).toHaveValue(10);
    expect(screen.getByLabelText(/plc code/i)).toHaveValue('PLC-1');
    expect(screen.getByRole('switch', { name: /clearing location/i })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('switch', { name: /exclude from automatic putaway/i })).toHaveAttribute('aria-checked', 'false');
    // Select-based fields render their prefilled label as text in the trigger.
    expect(screen.getByText('CHILLED')).toBeInTheDocument();
    expect(screen.getByText('HAZMAT')).toBeInTheDocument();
    expect(screen.getByText('RESERVE')).toBeInTheDocument();
  });

  it('edit submit sends the full field set, including the previously-orphaned ones, to updateLocation', async () => {
    const user = userEvent.setup();
    const mockUpdate = vi.fn().mockResolvedValue({});
    vi.mocked(hooks.useUpdateLocation).mockReturnValue({
      mutateAsync: mockUpdate,
      isPending: false,
    } as unknown as ReturnType<typeof hooks.useUpdateLocation>);

    render(
      <LocationForm level="locations" entity={existingLocation} parentId={10} onSave={onSave} onCancel={onCancel} />,
    );

    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(mockUpdate).toHaveBeenCalledWith({
      id: 5,
      data: expect.objectContaining({
        name: 'A1-B2',
        locationTypeId: 1,
        areaId: 10,
        capacity: 10,
        temperatureZone: 'CHILLED',
        handlingClass: 'HAZMAT',
        kind: 'RESERVE',
        plcCode: 'PLC-1',
        isClearing: false,
        allocationState: 0,
      }),
    });
    expect(onSave).toHaveBeenCalledTimes(1);
  });

  it('surfaces a 409 location-in-use conflict as an inline error next to the clearing switch', async () => {
    const user = userEvent.setup();
    const problem = {
      type: 'https://karyo.com/errors/location-in-use',
      title: 'Location In Use',
      status: 409,
      detail: 'another location is already configured as the clearing location',
    };
    const mockUpdate = vi.fn().mockRejectedValue(new ApiError(problem));
    vi.mocked(hooks.useUpdateLocation).mockReturnValue({
      mutateAsync: mockUpdate,
      isPending: false,
    } as unknown as ReturnType<typeof hooks.useUpdateLocation>);

    render(
      <LocationForm level="locations" entity={existingLocation} parentId={10} onSave={onSave} onCancel={onCancel} />,
    );

    await user.click(screen.getByRole('switch', { name: /clearing location/i }));
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(await screen.findByText(/already configured as the clearing location/i)).toBeInTheDocument();
    // The form stays open on conflict -- onSave must not fire.
    expect(onSave).not.toHaveBeenCalled();
  });
});
