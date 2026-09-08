import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import type { UnitLoadTypeResponse } from '@/types/inventory';

const permissionsState = vi.hoisted(() => ({
  permissions: ['user-admin', 'inventory-write'] as string[],
}));

vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: () => ({
    hasPermission: (p: string) => permissionsState.permissions.includes(p),
    hasAnyPermission: (ps: string[]) => ps.some((p) => permissionsState.permissions.includes(p)),
    permissions: permissionsState.permissions,
  }),
}));

class MockApiError extends Error {}

const EURO_PALLET: UnitLoadTypeResponse = {
  id: 1,
  name: 'Euro pallet',
  usages: 'PALLET',
  aggregateStocks: false,
  height: 144,
  width: 120,
  depth: 80,
  liftingCapacity: 1000,
  weight: 25,
  manageEmpties: true,
  created: '2026-08-01T00:00:00Z',
  modified: '2026-08-01T00:00:00Z',
};

const SHIPPING_CARTON: UnitLoadTypeResponse = {
  id: 2,
  name: 'Shipping carton',
  usages: 'CARTON',
  aggregateStocks: true,
  height: null,
  width: null,
  depth: null,
  liftingCapacity: null,
  weight: 0.5,
  manageEmpties: false,
  created: '2026-08-01T00:00:00Z',
  modified: '2026-08-01T00:00:00Z',
};

const apiGet = vi.fn();
const apiPost = vi.fn();
const apiPut = vi.fn();
const apiDelete = vi.fn();

vi.mock('@/lib/api-client', () => ({
  api: {
    get: (...args: unknown[]) => apiGet(...args),
    post: (...args: unknown[]) => apiPost(...args),
    put: (...args: unknown[]) => apiPut(...args),
    delete: (...args: unknown[]) => apiDelete(...args),
  },
  ApiError: MockApiError,
}));

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn() }),
}));

const { toast } = await import('sonner');
const { AdminUnitLoadTypesPage } = await import('../admin-unit-load-types-page');

function renderPage() {
  const qc = new QueryClient();
  return render(
    createElement(QueryClientProvider, { client: qc }, createElement(AdminUnitLoadTypesPage)),
  );
}

describe('AdminUnitLoadTypesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    permissionsState.permissions = ['user-admin', 'inventory-write'];
    apiGet.mockResolvedValue([EURO_PALLET, SHIPPING_CARTON]);
    apiPost.mockResolvedValue({ ...EURO_PALLET, id: 3 });
    apiPut.mockResolvedValue({ ...SHIPPING_CARTON, manageEmpties: true });
  });

  it('lists unit load types with their manageEmpties flag', async () => {
    renderPage();
    expect(await screen.findByText('Euro pallet')).toBeInTheDocument();
    expect(screen.getByText('Shipping carton')).toBeInTheDocument();
    expect(screen.getByText('Manage empties: On')).toBeInTheDocument();
    expect(screen.getByText('Manage empties: Off')).toBeInTheDocument();
  });

  it('creates a unit load type', async () => {
    renderPage();
    await screen.findByText('Euro pallet');
    await userEvent.click(screen.getByRole('button', { name: /new type/i }));
    await userEvent.type(screen.getByLabelText('Name'), 'Wire cage');
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() => expect(apiPost).toHaveBeenCalled());
    expect(apiPost).toHaveBeenCalledWith(
      '/api/v1/unit-load-types',
      expect.objectContaining({
        name: 'Wire cage',
        aggregateStocks: false,
        manageEmpties: false,
      }),
    );
  });

  it('updates manageEmpties on an existing type and re-reads the list', async () => {
    apiGet.mockResolvedValueOnce([EURO_PALLET, SHIPPING_CARTON]);
    apiGet.mockResolvedValueOnce([EURO_PALLET, { ...SHIPPING_CARTON, manageEmpties: true }]);

    renderPage();
    await userEvent.click(await screen.findByText('Shipping carton'));
    await userEvent.click(screen.getByRole('button', { name: /^edit$/i }));
    await userEvent.click(screen.getByLabelText('Manage empties'));
    await userEvent.click(screen.getByRole('button', { name: /^save$/i }));

    await waitFor(() => expect(apiPut).toHaveBeenCalled());
    // Full-representation body: every field present, not just the one that changed.
    expect(apiPut).toHaveBeenCalledWith('/api/v1/unit-load-types/2', {
      name: 'Shipping carton',
      usages: 'CARTON',
      aggregateStocks: true,
      height: null,
      width: null,
      depth: null,
      liftingCapacity: null,
      weight: 0.5,
      manageEmpties: true,
    });

    // Re-reads the list: the query is invalidated and refetched, and the
    // list row reflects the second (updated) GET response, not just local state.
    await waitFor(() => expect(apiGet).toHaveBeenCalledTimes(2));
    await waitFor(() => {
      const row = screen
        .getAllByText('Shipping carton')
        .map((el) => el.closest('button'))
        .find((el): el is HTMLButtonElement => el != null);
      expect(row).toBeDefined();
      expect(within(row as HTMLButtonElement).getByText('Manage empties: On')).toBeInTheDocument();
    });
  });

  it('surfaces a server-side validation error rather than failing silently', async () => {
    apiPost.mockRejectedValueOnce(new MockApiError('name must be unique'));

    renderPage();
    await screen.findByText('Euro pallet');
    await userEvent.click(screen.getByRole('button', { name: /new type/i }));
    await userEvent.type(screen.getByLabelText('Name'), 'Euro pallet');
    await userEvent.click(screen.getByRole('button', { name: /^create$/i }));

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith(expect.stringContaining('name must be unique')),
    );
    // The form stays open rather than silently discarding the attempt.
    expect(screen.getByRole('button', { name: /^create$/i })).toBeInTheDocument();
  });

  it('hides write controls for a user-admin principal without inventory-write', async () => {
    permissionsState.permissions = ['user-admin'];

    renderPage();
    await screen.findByText('Euro pallet');
    expect(screen.queryByRole('button', { name: /new type/i })).not.toBeInTheDocument();

    await userEvent.click(screen.getByText('Euro pallet'));
    expect(screen.queryByRole('button', { name: /^edit$/i })).not.toBeInTheDocument();
  });

  it('shows write controls for a principal with both user-admin and inventory-write', async () => {
    permissionsState.permissions = ['user-admin', 'inventory-write'];

    renderPage();
    await screen.findByText('Euro pallet');
    expect(screen.getByRole('button', { name: /new type/i })).toBeInTheDocument();

    await userEvent.click(screen.getByText('Euro pallet'));
    expect(screen.getByRole('button', { name: /^edit$/i })).toBeInTheDocument();
  });
});
