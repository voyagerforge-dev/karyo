import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import type { ClientResponse } from '@/types/client';

vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: () => ({
    hasPermission: () => true,
    hasAnyPermission: () => true,
    permissions: ['user-admin'],
  }),
}));

const createMutateAsync = vi.fn().mockResolvedValue({
  id: 4,
  name: 'Initech',
  number: 'CL-100',
  code: '',
  email: '',
  phone: '',
  fax: '',
  state: 'ACTIVE',
  isSystemClient: false,
});
const updateMutateAsync = vi.fn().mockResolvedValue({});
const setActiveMutate = vi.fn();
const consistencyRefetch = vi.fn();

const CLIENTS: ClientResponse[] = [
  { id: 0, name: 'SYS', number: 'CL-000', code: 'SYS', email: '', phone: '', fax: '', state: 'ACTIVE', isSystemClient: true },
  { id: 1, name: 'ACME', number: 'CL-001', code: 'ACME', email: 'ops@acme.test', phone: '', fax: '', state: 'ACTIVE', isSystemClient: false },
  { id: 2, name: 'GLOBEX', number: 'CL-002', code: 'GLBX', email: '', phone: '', fax: '', state: 'INACTIVE', isSystemClient: false },
];

vi.mock('@/features/clients/use-clients', () => ({
  useClients: () => ({ data: CLIENTS, isLoading: false, isSuccess: true }),
  useCreateClient: () => ({ mutateAsync: createMutateAsync, isPending: false }),
  useUpdateClient: () => ({ mutateAsync: updateMutateAsync, isPending: false }),
  useSetClientActive: () => ({ mutate: setActiveMutate, isPending: false }),
  useConsistencyCheck: () => ({ data: undefined, refetch: consistencyRefetch, isFetching: false }),
}));

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn() }),
}));

const { AdminClientsPage } = await import('../admin-clients-page');

function renderPage() {
  const qc = new QueryClient();
  return render(
    createElement(QueryClientProvider, { client: qc }, createElement(AdminClientsPage)),
  );
}

describe('AdminClientsPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists clients with state and system marker', () => {
    renderPage();
    expect(screen.getByText('SYS')).toBeInTheDocument();
    expect(screen.getByText('System')).toBeInTheDocument(); // system-client chip
    expect(screen.getAllByText('Inactive').length).toBeGreaterThan(0);
  });

  // docs/user-guide/administer-karyo.md describes this screen as searched, not
  // filtered: read-only Active/Inactive count tiles, a per-row state pill, one
  // search box over name and number, and an empty detail pane until a row is
  // clicked. Each of those claims is asserted here so a future filter-chip row
  // (or an auto-selected first client) fails rather than silently contradicting
  // the guide.
  it('narrows by search only - count tiles and row pills, no state chips, empty detail until clicked', async () => {
    renderPage();

    expect(screen.getByText('Select a client to view its details.')).toBeInTheDocument();
    expect(screen.getByText('Total clients').nextElementSibling).toHaveTextContent('3');
    expect(screen.getByText('Active', { selector: 'div' }).nextElementSibling).toHaveTextContent('2');
    expect(screen.getByText('Inactive', { selector: 'div' }).nextElementSibling).toHaveTextContent('1');
    expect(screen.queryByRole('button', { name: /^(all|active|inactive|system)$/i })).not.toBeInTheDocument();

    const search = screen.getByPlaceholderText(/search clients/i);
    await userEvent.type(search, 'CL-002');
    expect(screen.getByText('GLOBEX')).toBeInTheDocument();
    expect(screen.queryByText('ACME')).not.toBeInTheDocument();
    expect(screen.queryByText('SYS')).not.toBeInTheDocument();
  });

  it('creates a client', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /new client/i }));
    await userEvent.type(screen.getByLabelText('Name'), 'Initech');
    await userEvent.type(screen.getByLabelText('Number'), 'CL-100');
    await userEvent.click(screen.getByRole('button', { name: /create/i }));
    expect(createMutateAsync).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Initech', number: 'CL-100' }),
    );
  });

  it('runs the consistency check on demand', async () => {
    renderPage();
    await userEvent.click(screen.getByRole('button', { name: /run check/i }));
    expect(consistencyRefetch).toHaveBeenCalled();
  });

  it('does not offer deactivate for the system client', async () => {
    renderPage();
    await userEvent.click(screen.getByText('SYS'));
    expect(screen.queryByRole('button', { name: /deactivate/i })).not.toBeInTheDocument();
  });

  it('offers deactivate for a non-system active client and calls useSetClientActive', async () => {
    renderPage();
    await userEvent.click(screen.getByText('ACME'));
    await userEvent.click(screen.getByRole('button', { name: /deactivate/i }));
    expect(setActiveMutate).toHaveBeenCalledWith(false, expect.anything());
  });
});
