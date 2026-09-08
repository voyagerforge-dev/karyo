import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, within } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { RECEIVING_STATE, type AsnResponse, type UlAdviceResponse } from '@/types/receiving';

// ProductPicker (advice add-row) hits the network -- keep it quiet.
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

// Mock idiom from pages/inventory/__tests__/inventory-detail.test.tsx:17-20.
const saveZpl = vi.fn();
vi.mock('@/lib/document-actions', () => ({
  saveZpl: (u: string, f: string) => saveZpl(u, f),
}));

const releaseMutate = vi.fn();
const cancelMutate = vi.fn();
const finishMutate = vi.fn();
const createAdviceMutate = vi.fn();
const deleteAdviceMutate = vi.fn();

vi.mock('../use-asns', () => ({
  useAsn: vi.fn(),
  useReleaseAsn: vi.fn(() => ({ mutate: releaseMutate, isPending: false })),
  useCancelAsn: vi.fn(() => ({ mutate: cancelMutate, isPending: false })),
  useFinishAsn: vi.fn(() => ({ mutate: finishMutate, isPending: false })),
  useCreateUlAdvice: vi.fn(() => ({ mutate: createAdviceMutate, isPending: false })),
  useDeleteUlAdvice: vi.fn(() => ({ mutate: deleteAdviceMutate, isPending: false })),
}));

vi.mock('@/pages/receiving/use-receiving', () => ({
  useCreateGoodsReceipt: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
}));

// Import after mocks
import { useAsn } from '../use-asns';
import { AsnDetail } from '../asn-detail';

function baseAsn(overrides: Partial<AsnResponse> = {}): AsnResponse {
  return {
    id: 10,
    asnNumber: 'ASN-2010',
    externalNumber: null,
    carrierName: 'FedEx',
    supplierName: null,
    senderName: null,
    expectedDate: null,
    notes: null,
    state: RECEIVING_STATE.CREATED,
    stateName: 'CREATED',
    clientId: 1,
    progressPercent: 0,
    lines: [],
    created: '2026-08-01T08:00:00Z',
    modified: '2026-08-01T08:00:00Z',
    ulAdvices: [],
    ...overrides,
  };
}

const advice1: UlAdviceResponse = {
  id: 1,
  labelId: 'ULA-0001',
  unitLoadTypeId: null,
  itemDataId: 5,
  itemDataNumber: 'DEMO-MOUSE',
  expectedAmount: 10,
  reasonForReturn: null,
  state: 50,
  stateName: 'CREATED',
  matchedReceiptLineId: null,
};

const advice2: UlAdviceResponse = {
  id: 2,
  labelId: 'ULA-0002',
  unitLoadTypeId: 3,
  itemDataId: null,
  itemDataNumber: null,
  expectedAmount: null,
  reasonForReturn: 'Damaged',
  state: 700,
  stateName: 'FINISHED',
  matchedReceiptLineId: 99,
};

function renderDetail(asn: AsnResponse, canWrite = true) {
  vi.mocked(useAsn).mockReturnValue({
    data: asn,
    isLoading: false,
  } as ReturnType<typeof useAsn>);

  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <AsnDetail asnId={asn.id} canWrite={canWrite} onEdit={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  saveZpl.mockClear();
  releaseMutate.mockClear();
  cancelMutate.mockClear();
  finishMutate.mockClear();
  createAdviceMutate.mockClear();
  deleteAdviceMutate.mockClear();
});

describe('AsnDetail — UL pre-advices', () => {
  it('renders advice rows with label, product, expected amount, reason and state pill', () => {
    renderDetail(baseAsn({ ulAdvices: [advice1, advice2] }));

    const row1 = screen.getByTestId('ul-advice-row-1');
    expect(within(row1).getByText('ULA-0001')).toBeInTheDocument();
    expect(within(row1).getByText('DEMO-MOUSE')).toBeInTheDocument();
    expect(within(row1).getByText('10.00')).toBeInTheDocument();
    expect(within(row1).getByText('CREATED')).toBeInTheDocument();

    const row2 = screen.getByTestId('ul-advice-row-2');
    expect(within(row2).getByText('ULA-0002')).toBeInTheDocument();
    expect(within(row2).getByText('Damaged')).toBeInTheDocument();
    expect(within(row2).getByText('FINISHED')).toBeInTheDocument();
  });

  it('shows an honest empty state when no advices are registered', () => {
    renderDetail(baseAsn());
    expect(screen.getByText('No unit load pre-advices registered.')).toBeInTheDocument();
  });

  it('print button calls saveZpl with the ZPL export URL and a filename', async () => {
    const user = userEvent.setup();
    renderDetail(baseAsn({ ulAdvices: [advice1] }));

    await user.click(screen.getByTestId('asn-print-ul-labels'));

    expect(saveZpl).toHaveBeenCalledWith(
      '/api/v1/asns/10/ul-labels.zpl',
      'asn-ASN-2010-ul-labels.zpl',
    );
  });

  it('print button is disabled when there are no advices', () => {
    renderDetail(baseAsn());
    expect(screen.getByTestId('asn-print-ul-labels')).toBeDisabled();
  });

  it('add form and remove buttons are hidden without order-write', () => {
    renderDetail(baseAsn({ ulAdvices: [advice1] }), false);

    expect(screen.queryByTestId('asn-ul-advice-form')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ul-advice-remove-1')).not.toBeInTheDocument();
  });

  it('add form and remove buttons are hidden once receiving has started', () => {
    renderDetail(
      baseAsn({ state: RECEIVING_STATE.STARTED, stateName: 'STARTED', ulAdvices: [advice1] }),
    );

    expect(screen.queryByTestId('asn-ul-advice-form')).not.toBeInTheDocument();
    expect(screen.queryByTestId('ul-advice-remove-1')).not.toBeInTheDocument();
  });

  it('add form and remove buttons are offered while RELEASED (not just CREATED)', () => {
    renderDetail(
      baseAsn({ state: RECEIVING_STATE.RELEASED, stateName: 'RELEASED', ulAdvices: [advice1] }),
    );

    expect(screen.getByTestId('asn-ul-advice-form')).toBeInTheDocument();
    expect(screen.getByTestId('ul-advice-remove-1')).toBeInTheDocument();
  });

  it('adds a pre-advice via the form', async () => {
    const user = userEvent.setup();
    renderDetail(baseAsn());

    await user.type(screen.getByLabelText(/^label$/i), 'ULA-NEW');
    await user.click(screen.getByTestId('asn-ul-advice-add'));

    expect(createAdviceMutate).toHaveBeenCalledWith(
      expect.objectContaining({ asnId: 10, labelId: 'ULA-NEW' }),
      expect.anything(),
    );
  });

  it('removes a pre-advice', async () => {
    const user = userEvent.setup();
    renderDetail(baseAsn({ ulAdvices: [advice1] }));

    await user.click(screen.getByTestId('ul-advice-remove-1'));

    expect(deleteAdviceMutate).toHaveBeenCalledWith({ asnId: 10, adviceId: 1 });
  });
});
