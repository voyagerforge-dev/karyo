import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';

// AsnPicker (via useAsns) and LocationPicker both call api.get directly —
// route by URL so one mock satisfies both pickers.
vi.mock('@/lib/api-client', () => ({
  api: {
    get: vi.fn((url: string) => {
      if (url.startsWith('/api/v1/asns')) {
        return Promise.resolve({
          content: [{ id: 3, asnNumber: 'ASN-0003', state: 100, carrierName: 'Speedy' }],
          page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
        });
      }
      return Promise.resolve({
        content: [{ id: 7, name: 'RCV-01', area: { name: 'Receiving Area A' } }],
        page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
      });
    }),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
}));

const navigateMock = vi.fn();
vi.mock('react-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('react-router')>();
  return { ...actual, useNavigate: () => navigateMock };
});

const createMutate = vi.fn();
vi.mock('../use-receiving', () => ({
  useCreateGoodsReceipt: vi.fn(() => ({ mutate: createMutate, isPending: false })),
}));

import { NewReceiptForm } from '../new-receipt-form';

function renderForm() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <MemoryRouter>
        <NewReceiptForm onClose={vi.fn()} />
      </MemoryRouter>
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  createMutate.mockReset();
});

describe('NewReceiptForm', () => {
  it('RETOUR type disables and clears the ASN picker', async () => {
    const user = userEvent.setup();
    renderForm();

    // Bind an ASN first via the searchable picker.
    const asnInput = screen.getByPlaceholderText(/search asn/i);
    await user.type(asnInput, 'ASN');
    await user.click(await screen.findByRole('option', { name: /ASN-0003/i }));
    expect(screen.getByText('ASN-0003')).toBeInTheDocument();

    // Switch to RETOUR — the ASN picker should disappear/disable and clear.
    await user.click(screen.getByTestId('receipt-type-retour'));

    expect(screen.queryByText('ASN-0003')).not.toBeInTheDocument();
    expect(screen.queryByPlaceholderText(/search asn/i)).not.toBeInTheDocument();
    expect(screen.getByText(/customer returns arrive blind/i)).toBeInTheDocument();

    // Verify the payload path: fill required fields and submit, then assert asnIds is empty.
    const dockInput = screen.getByPlaceholderText(/search location/i);
    await user.type(dockInput, 'RCV');
    await user.click(await screen.findByRole('option', { name: /RCV-01/i }));

    await user.click(screen.getByTestId('new-receipt-submit'));

    expect(createMutate).toHaveBeenCalledTimes(1);
    const payload = createMutate.mock.calls[0][0];
    expect(payload).toMatchObject({
      receiptType: 1, // GOODS_RECEIPT_TYPE.RETOUR
      asnIds: [],
      dockLocationId: 7,
      dockLocationName: 'RCV-01',
    });
  });

  it('creates with type, prio, receiptDate and dock', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByTestId('receipt-type-retour'));

    const prioInput = screen.getByLabelText(/prio/i);
    await user.type(prioInput, '75');

    const dateInput = screen.getByLabelText(/receipt date/i);
    await user.type(dateInput, '2026-07-21');

    const dockInput = screen.getByPlaceholderText(/search location/i);
    await user.type(dockInput, 'RCV');
    await user.click(await screen.findByRole('option', { name: /RCV-01/i }));

    await user.click(screen.getByTestId('new-receipt-submit'));

    expect(createMutate).toHaveBeenCalledTimes(1);
    const payload = createMutate.mock.calls[0][0];
    expect(payload).toMatchObject({
      receiptType: 1,
      prio: 75,
      receiptDate: '2026-07-21',
      dockLocationId: 7,
      dockLocationName: 'RCV-01',
    });
  });

  it('omits optional fields left empty', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByTestId('new-receipt-submit'));

    expect(createMutate).toHaveBeenCalledTimes(1);
    const payload = createMutate.mock.calls[0][0];
    expect(payload).toMatchObject({
      receiptType: 0,
      prio: undefined,
      receiptDate: undefined,
      dockLocationId: undefined,
      dockLocationName: undefined,
    });
  });
});
