import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';

// `integration-admin` (not `user-admin`) is the gate this page checks post-rider
// (re-gate 2026-07-25) -- `manager` holds the former without the latter.
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: () => ({
    hasPermission: (p: string) => p === 'integration-admin',
    hasAnyPermission: (ps: string[]) => ps.includes('integration-admin'),
    permissions: ['integration-admin'],
  }),
}));

const mockMutateAsync = vi.fn();

const subs = [
  { id: 1, name: 'Acme ERP', targetUrl: 'https://acme.test/hooks', eventTypes: ['DeliveryOrder*'], active: true },
];
vi.mock('@/features/webhooks/use-webhooks', () => ({
  useSubscriptions: () => ({ data: subs, isSuccess: true, isLoading: false }),
  useDeliveries: () => ({ data: [], isSuccess: true, isLoading: false }),
  useCreateSubscription: () => ({ mutate: vi.fn(), mutateAsync: mockMutateAsync, isPending: false }),
  useUpdateSubscription: () => ({ mutate: vi.fn(), isPending: false }),
  useDeleteSubscription: () => ({ mutate: vi.fn(), isPending: false }),
  useTestSubscription: () => ({ mutate: vi.fn(), isPending: false }),
  useRedeliver: () => ({ mutate: vi.fn(), isPending: false }),
}));

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn() }),
}));

const { AdminIntegrationsPage } = await import('@/pages/admin/admin-integrations-page');

function renderPage() {
  const qc = new QueryClient();
  return render(
    createElement(QueryClientProvider, { client: qc }, createElement(AdminIntegrationsPage)),
  );
}

describe('AdminIntegrationsPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('lists subscriptions', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('Acme ERP')).toBeInTheDocument());
  });

  it('renders the page testid', () => {
    renderPage();
    expect(screen.getByTestId('admin-integrations-page')).toBeInTheDocument();
  });

  it('shows toast.error when create mutation rejects', async () => {
    const { toast } = await import('sonner');
    mockMutateAsync.mockRejectedValueOnce(new Error('SSRF: loopback addresses are not permitted'));

    renderPage();

    // Open the create form
    fireEvent.click(screen.getByText('+ New subscription'));

    // Fill in required fields — the search bar in MasterList is inputs[0];
    // form fields follow: Name=inputs[1], Target URL=inputs[2], Events=inputs[3]
    const inputs = screen.getAllByRole('textbox') as HTMLInputElement[];
    fireEvent.change(inputs[1], { target: { value: 'Test Hook' } });
    fireEvent.change(inputs[2], { target: { value: 'http://127.0.0.1/hook' } });
    // inputs[3] events defaults to '*' — leave it

    // Submit
    fireEvent.click(screen.getByText('Create'));

    await waitFor(() =>
      expect(toast.error).toHaveBeenCalledWith('SSRF: loopback addresses are not permitted'),
    );
  });
});
