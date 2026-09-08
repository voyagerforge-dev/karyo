import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { QueryClientProvider, QueryClient } from '@tanstack/react-query';
import { createElement } from 'react';

const mockApi = { get: vi.fn(), patch: vi.fn(), post: vi.fn(), put: vi.fn(), delete: vi.fn() };
vi.mock('@/lib/api-client', () => ({ api: mockApi }));

const { AdminAuditPage } = await import('../admin-audit-page');

const JOURNAL_ENTRIES = [
  {
    id: 2,
    recordType: 3,
    recordTypeName: 'PICK',
    productNumber: 'SKU-100',
    productName: 'Wireless Mouse',
    amount: 5,
    fromStorageLocation: 'A-01-01',
    toStorageLocation: null,
    lotNumber: null,
    correlationId: 'ORD-42',
    operatorName: 'jdoe',
    created: '2026-07-17T10:00:00Z',
  },
  {
    id: 1,
    recordType: 1,
    recordTypeName: 'RECEIPT',
    productNumber: 'SKU-200',
    productName: null,
    amount: 20,
    fromStorageLocation: null,
    toStorageLocation: 'B-02-02',
    lotNumber: 'LOT-1',
    correlationId: null,
    operatorName: null,
    created: '2026-07-16T08:00:00Z',
  },
];

const LOGIN_ENTRY = {
  id: 3,
  recordType: 10,
  recordTypeName: 'LOGIN',
  productNumber: null,
  productName: null,
  amount: null,
  fromStorageLocation: null,
  toStorageLocation: null,
  lotNumber: null,
  activityCode: 'LOGIN',
  correlationId: null,
  operatorName: 'jdoe',
  created: '2026-07-18T09:00:00Z',
  ipAddress: '203.0.113.7',
};

function renderPage() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(createElement(QueryClientProvider, { client: qc }, createElement(AdminAuditPage)));
}

describe('AdminAuditPage', () => {
  beforeEach(() => vi.clearAllMocks());

  it('GETs /api/v1/journals with no location filter and renders real rows', async () => {
    mockApi.get.mockResolvedValue(JOURNAL_ENTRIES);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-audit-table')).toBeInTheDocument());
    expect(mockApi.get).toHaveBeenCalledWith('/api/v1/journals');
    expect(screen.getByText('Wireless Mouse')).toBeInTheDocument();
    expect(screen.getByText('SKU-200')).toBeInTheDocument();
    expect(screen.getByText('ORD-42')).toBeInTheDocument();
    expect(screen.getByText('jdoe')).toBeInTheDocument();
    expect(screen.getByText('Pick')).toBeInTheDocument();
    expect(screen.getByText('Receipt')).toBeInTheDocument();
  });

  it('shows an honest empty state when there are no journal events', async () => {
    mockApi.get.mockResolvedValue([]);
    renderPage();

    await waitFor(() => expect(screen.getByText('No journal events yet.')).toBeInTheDocument());
    expect(screen.queryByTestId('admin-audit-table')).not.toBeInTheDocument();
  });

  it('shows an honest error state on failure', async () => {
    mockApi.get.mockRejectedValue(new Error('500'));
    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-audit-error')).toBeInTheDocument());
  });

  it('shows a loading state before data resolves', () => {
    mockApi.get.mockReturnValue(new Promise(() => {}));
    renderPage();
    expect(screen.getByText('Loading audit log…')).toBeInTheDocument();
  });

  it('caps the rendered rows at 200 and shows an honest "most recent" note when the set is larger', async () => {
    const bigSet = Array.from({ length: 250 }, (_, i) => ({
      id: 250 - i,
      recordType: 3,
      recordTypeName: 'PICK',
      productNumber: `SKU-${i}`,
      productName: null,
      amount: 1,
      fromStorageLocation: null,
      toStorageLocation: null,
      lotNumber: null,
      correlationId: null,
      operatorName: null,
      created: '2026-07-17T10:00:00Z',
    }));
    mockApi.get.mockResolvedValue(bigSet);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-audit-table')).toBeInTheDocument());
    expect(screen.getAllByTestId(/^admin-audit-row-/)).toHaveLength(200);
    expect(screen.getByTestId('admin-audit-cap-note')).toHaveTextContent(
      'Showing the 200 most recent entries (of 250).',
    );
  });

  it('does not show the cap note when the set is at or under the limit', async () => {
    mockApi.get.mockResolvedValue(JOURNAL_ENTRIES);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-audit-table')).toBeInTheDocument());
    expect(screen.queryByTestId('admin-audit-cap-note')).not.toBeInTheDocument();
  });

  it('renders a Login auth row with its activity code and source IP, alongside unaffected inventory rows', async () => {
    mockApi.get.mockResolvedValue([...JOURNAL_ENTRIES, LOGIN_ENTRY]);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-audit-table')).toBeInTheDocument());
    expect(screen.getByText('Login')).toBeInTheDocument();
    expect(screen.getByText('LOGIN')).toBeInTheDocument();
    expect(screen.getByText('203.0.113.7')).toBeInTheDocument();
    // Existing inventory rows still render exactly as before (regression check).
    expect(screen.getByText('Wireless Mouse')).toBeInTheDocument();
    expect(screen.getByText('SKU-200')).toBeInTheDocument();
    expect(screen.getByText('Pick')).toBeInTheDocument();
    expect(screen.getByText('Receipt')).toBeInTheDocument();
  });

  it('narrows to auth rows when the Auth filter is selected', async () => {
    const user = userEvent.setup();
    mockApi.get.mockResolvedValue([...JOURNAL_ENTRIES, LOGIN_ENTRY]);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-audit-table')).toBeInTheDocument());
    expect(screen.getAllByTestId(/^admin-audit-row-/)).toHaveLength(3);

    await user.click(screen.getByTestId('admin-audit-filter-type'));
    await user.click(await screen.findByText('Auth'));

    await waitFor(() => expect(screen.getAllByTestId(/^admin-audit-row-/)).toHaveLength(1));
    expect(screen.getByText('Login')).toBeInTheDocument();
    expect(screen.queryByText('Wireless Mouse')).not.toBeInTheDocument();
    expect(screen.queryByText('SKU-200')).not.toBeInTheDocument();
  });

  it('narrows to inventory rows when the Inventory filter is selected', async () => {
    const user = userEvent.setup();
    mockApi.get.mockResolvedValue([...JOURNAL_ENTRIES, LOGIN_ENTRY]);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-audit-table')).toBeInTheDocument());

    await user.click(screen.getByTestId('admin-audit-filter-type'));
    await user.click(await screen.findByText('Inventory'));

    await waitFor(() => expect(screen.getAllByTestId(/^admin-audit-row-/)).toHaveLength(2));
    expect(screen.getByText('Pick')).toBeInTheDocument();
    expect(screen.getByText('Receipt')).toBeInTheDocument();
    expect(screen.queryByText('Login')).not.toBeInTheDocument();
  });

  it('shows an honest "no events match this filter" state without hiding the filter control', async () => {
    const user = userEvent.setup();
    mockApi.get.mockResolvedValue(JOURNAL_ENTRIES);
    renderPage();

    await waitFor(() => expect(screen.getByTestId('admin-audit-table')).toBeInTheDocument());

    await user.click(screen.getByTestId('admin-audit-filter-type'));
    await user.click(await screen.findByText('Auth'));

    await waitFor(() =>
      expect(screen.getByTestId('admin-audit-filter-empty')).toBeInTheDocument(),
    );
    expect(screen.queryByTestId('admin-audit-table')).not.toBeInTheDocument();
    expect(screen.getByTestId('admin-audit-filter-type')).toBeInTheDocument();
  });
});
