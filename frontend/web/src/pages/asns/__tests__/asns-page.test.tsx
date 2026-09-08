import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { PaginatedResponse } from '@/types/api';
import type { AsnResponse } from '@/types/receiving';

// Mock the API client so the create form's ProductPicker never hits the network.
vi.mock('@/lib/api-client', () => ({
  api: {
    get: vi.fn(() =>
      Promise.resolve({
        content: [],
        page: { number: 0, size: 100, totalElements: 0, totalPages: 0 },
      }),
    ),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
}));

const createMutate = vi.fn();
const releaseMutate = vi.fn();
vi.mock('../use-asns', () => ({
  useAsns: vi.fn(),
  useAsn: vi.fn(),
  useCreateAsn: vi.fn(() => ({ mutate: createMutate, isPending: false })),
  useUpdateAsn: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useReleaseAsn: vi.fn(() => ({ mutate: releaseMutate, isPending: false })),
  useCancelAsn: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useFinishAsn: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useCreateUlAdvice: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useDeleteUlAdvice: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  sortingStateToString: vi.fn(() => undefined),
}));

// The detail workspace pulls in the receipt-create hook; stub the module.
vi.mock('@/pages/receiving/use-receiving', () => ({
  useCreateGoodsReceipt: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
}));

let mockCanWrite = true;
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: ['order-read', 'order-write'],
    hasPermission: (p: string) => (p === 'order-write' ? mockCanWrite : p === 'order-read'),
    hasAnyPermission: () => true,
  })),
}));

const mockAsns: AsnResponse[] = [
  {
    id: 1,
    asnNumber: 'ASN-1001',
    externalNumber: 'PO-77',
    carrierName: 'FedEx',
    supplierName: 'Acme Supply Co',
    senderName: 'Acme Logistics',
    expectedDate: '2026-06-20',
    notes: null,
    state: 50,
    stateName: 'CREATED',
    clientId: 1,
    progressPercent: 0,
    lines: [
      {
        id: 11,
        lineNumber: 1,
        itemDataId: 5,
        itemDataNumber: 'DEMO-MOUSE',
        expectedAmount: 100,
        receivedAmount: 0,
        remainingAmount: 100,
        progressPercent: 0,
        state: 50,
        stateName: 'CREATED',
        lotNumber: null,
      },
    ],
    created: '2026-06-12T08:00:00Z',
    modified: '2026-06-12T08:00:00Z',
    ulAdvices: [],
  },
  {
    id: 2,
    asnNumber: 'ASN-1002',
    externalNumber: null,
    carrierName: 'UPS',
    supplierName: null,
    senderName: null,
    expectedDate: null,
    notes: null,
    state: 500,
    stateName: 'STARTED',
    clientId: 1,
    progressPercent: 50,
    lines: [],
    created: '2026-06-12T09:00:00Z',
    modified: '2026-06-12T09:00:00Z',
    ulAdvices: [],
  },
];

const mockPaginatedResponse: PaginatedResponse<AsnResponse> = {
  content: mockAsns,
  page: { number: 0, size: 50, totalElements: 2, totalPages: 1 },
};

// Import after mocks
import { useAsns, useAsn } from '../use-asns';
import { AsnsPage } from '../asns-page';

function renderPage() {
  const queryClient = new QueryClient({
    defaultOptions: { queries: { retry: false } },
  });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AsnsPage />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  createMutate.mockClear();
  releaseMutate.mockClear();
  vi.mocked(useAsns).mockReturnValue({
    data: mockPaginatedResponse,
    isLoading: false,
    isError: false,
    error: null,
  } as ReturnType<typeof useAsns>);
  vi.mocked(useAsn).mockImplementation(
    (id?: number) =>
      ({
        data: mockAsns.find((a) => a.id === id),
        isLoading: false,
      }) as ReturnType<typeof useAsn>,
  );
});

afterEach(() => {
  mockCanWrite = true;
});

describe('AsnsPage', () => {
  it('lists ASNs as master rows with state pill and progress', () => {
    renderPage();

    expect(screen.getByText('ASN-1001')).toBeInTheDocument();
    expect(screen.getByText('ASN-1002')).toBeInTheDocument();
    // getAsnStatus labels: CREATED -> Created, STARTED -> Receiving (scope past
    // the identically-labeled filter chips by asserting within each row).
    const row1 = screen.getByText('ASN-1001').closest('button') as HTMLElement;
    const row2 = screen.getByText('ASN-1002').closest('button') as HTMLElement;
    expect(within(row1).getByText('Created')).toBeInTheDocument();
    expect(within(row2).getByText('Receiving')).toBeInTheDocument();
  });

  it('selecting a row renders the inline detail workspace with supplier and sender', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByText('ASN-1001'));

    expect(screen.getByTestId('asn-detail-state')).toBeInTheDocument();
    expect(screen.getByText('Acme Supply Co')).toBeInTheDocument();
    expect(screen.getByText('Acme Logistics')).toBeInTheDocument();
  });

  it('filter chips partition by status', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: 'Receiving' }));

    expect(screen.queryByText('ASN-1001')).not.toBeInTheDocument();
    expect(screen.getByText('ASN-1002')).toBeInTheDocument();
  });

  it('release action calls the hook from the detail pane', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByText('ASN-1001'));
    await user.click(screen.getByRole('button', { name: /^release$/i }));

    expect(releaseMutate).toHaveBeenCalledWith(1);
  });

  it('hides write actions without order-write', async () => {
    mockCanWrite = false;
    const user = userEvent.setup();
    renderPage();

    expect(screen.queryByRole('button', { name: /new asn/i })).not.toBeInTheDocument();

    await user.click(screen.getByText('ASN-1001'));

    expect(screen.queryByRole('button', { name: /^release$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^cancel$/i })).not.toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /^edit$/i })).not.toBeInTheDocument();
  });

  it('opens the create form with header fields incl. supplier and sender', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /new asn/i }));

    expect(screen.getByTestId('asn-form-submit')).toBeInTheDocument();
    expect(screen.getByLabelText(/^supplier$/i)).toBeInTheDocument();
    expect(screen.getByLabelText(/^sender$/i)).toBeInTheDocument();
  });

  it('create form validates that at least one line is filled', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByRole('button', { name: /new asn/i }));
    await user.click(screen.getByTestId('asn-form-submit'));

    expect(screen.getByText(/at least one line with a product/i)).toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();
  });
});
