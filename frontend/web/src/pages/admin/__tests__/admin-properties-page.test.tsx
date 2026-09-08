import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor, fireEvent, within } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import { createElement } from 'react';
import type { SystemPropertyView } from '@/types/system-property';

vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: () => ({
    hasPermission: (p: string) => p === 'user-admin',
    hasAnyPermission: (ps: string[]) => ps.includes('user-admin'),
    permissions: ['user-admin'],
  }),
}));

const properties: SystemPropertyView[] = [
  {
    key: 'demo.enabled',
    context: null,
    clientId: 0,
    value: 'true',
    source: 'SYSTEM',
    type: 'BOOLEAN',
    group: 'Demo',
    description: 'Enable the living-warehouse demo generator.',
    defaultValue: 'false',
    secret: false,
    ownerWritable: true,
  },
  {
    key: 'receiving.overReceiptAllowed',
    context: null,
    clientId: 1,
    value: 'false',
    source: 'CLIENT',
    type: 'BOOLEAN',
    group: 'Receiving',
    description: 'Allow receiving more than the ASN advised quantity.',
    defaultValue: 'true',
    secret: false,
    ownerWritable: false,
  },
  {
    key: 'custom.note',
    context: null,
    clientId: 1,
    value: 'hello',
    source: 'CLIENT',
    type: null,
    group: null,
    description: null,
    defaultValue: null,
    secret: false,
    ownerWritable: true,
  },
  {
    key: 'receiving.leadTimeDays',
    context: 'client:2',
    clientId: 2,
    value: '5',
    source: 'CLIENT',
    type: 'INTEGER',
    group: 'Receiving',
    description: 'Lead time override for a single goods owner.',
    defaultValue: '7',
    secret: false,
    ownerWritable: true,
  },
];

const mockSet = vi.fn();
const mockReset = vi.fn();

vi.mock('@/pages/admin/use-system-properties', () => ({
  useSystemProperties: () => ({ data: properties, isSuccess: true, isLoading: false }),
  useSetSystemProperty: () => ({ mutateAsync: mockSet, isPending: false }),
  useResetSystemProperty: () => ({ mutateAsync: mockReset, isPending: false }),
}));

vi.mock('sonner', () => ({
  toast: Object.assign(vi.fn(), { error: vi.fn() }),
}));

const { AdminPropertiesPage } = await import('@/pages/admin/admin-properties-page');

function renderPage() {
  const qc = new QueryClient();
  return render(
    createElement(QueryClientProvider, { client: qc }, createElement(AdminPropertiesPage)),
  );
}

describe('AdminPropertiesPage', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    mockSet.mockResolvedValue(properties[0]);
    mockReset.mockResolvedValue(undefined);
  });

  it('renders the page testid', () => {
    renderPage();
    expect(screen.getByTestId('admin-properties-page')).toBeInTheDocument();
  });

  it('renders groups from mocked data', async () => {
    renderPage();
    await waitFor(() => expect(screen.getByText('Demo')).toBeInTheDocument());
    expect(screen.getByText('Receiving')).toBeInTheDocument();
    // Non-catalog stored row (group: null) is bucketed under "Custom".
    expect(screen.getByText('Custom')).toBeInTheDocument();
    expect(screen.getByText('custom.note')).toBeInTheDocument();
  });

  it('toggling a boolean fires PUT with {value: "false"}', async () => {
    renderPage();
    const row = within(await screen.findByTestId('property-row-demo.enabled'));
    fireEvent.click(row.getByRole('switch'));

    await waitFor(() =>
      expect(mockSet).toHaveBeenCalledWith({
        key: 'demo.enabled',
        body: { value: 'false', context: undefined },
      }),
    );
  });

  it('reset fires DELETE', async () => {
    renderPage();
    const row = within(await screen.findByTestId('property-row-custom.note'));
    fireEvent.click(row.getByText('Reset to default'));

    await waitFor(() =>
      expect(mockReset).toHaveBeenCalledWith({ key: 'custom.note', context: undefined }),
    );
  });

  it('renders the source pill text for each row', async () => {
    renderPage();
    const demoRow = within(await screen.findByTestId('property-row-demo.enabled'));
    expect(demoRow.getByText('SYSTEM')).toBeInTheDocument();

    const receivingRow = within(
      await screen.findByTestId('property-row-receiving.overReceiptAllowed'),
    );
    expect(receivingRow.getByText('CLIENT')).toBeInTheDocument();
    // ownerWritable: false hints "Ops-controlled" — the attempt is still allowed;
    // a real 403 surfaces via api-client's own toast.
    expect(receivingRow.getByText('Ops-controlled')).toBeInTheDocument();

    const customRow = within(await screen.findByTestId('property-row-custom.note'));
    expect(customRow.getByText('CLIENT')).toBeInTheDocument();
    expect(customRow.queryByText('Ops-controlled')).not.toBeInTheDocument();
  });

  it('shows a context badge only for a row that carries a context', async () => {
    renderPage();
    const contextRow = within(
      await screen.findByTestId('property-row-receiving.leadTimeDays'),
    );
    expect(contextRow.getByTestId('property-context-badge-receiving.leadTimeDays')).toHaveTextContent(
      'client:2',
    );

    const demoRow = within(await screen.findByTestId('property-row-demo.enabled'));
    expect(
      demoRow.queryByTestId('property-context-badge-demo.enabled'),
    ).not.toBeInTheDocument();
  });

  it('Add context override reveals a form, and submitting it PUTs the new context row', async () => {
    renderPage();
    const row = within(await screen.findByTestId('property-row-demo.enabled'));

    expect(row.queryByTestId('property-override-form-demo.enabled')).not.toBeInTheDocument();
    fireEvent.click(row.getByTestId('property-add-override-demo.enabled'));

    const form = within(await screen.findByTestId('property-override-form-demo.enabled'));
    fireEvent.change(form.getByLabelText('Override context for demo.enabled'), {
      target: { value: 'client:3' },
    });
    fireEvent.change(form.getByLabelText('Override value for demo.enabled'), {
      target: { value: 'true' },
    });
    fireEvent.click(form.getByText('Save override'));

    await waitFor(() =>
      expect(mockSet).toHaveBeenCalledWith({
        key: 'demo.enabled',
        body: { value: 'true', context: 'client:3' },
      }),
    );
  });

  it('the override submit button stays disabled until both context and value are filled', async () => {
    renderPage();
    const row = within(await screen.findByTestId('property-row-demo.enabled'));
    fireEvent.click(row.getByTestId('property-add-override-demo.enabled'));

    const form = within(await screen.findByTestId('property-override-form-demo.enabled'));
    expect(form.getByTestId('property-override-submit-demo.enabled')).toBeDisabled();

    fireEvent.change(form.getByLabelText('Override context for demo.enabled'), {
      target: { value: 'client:3' },
    });
    expect(form.getByTestId('property-override-submit-demo.enabled')).toBeDisabled();
  });
});
