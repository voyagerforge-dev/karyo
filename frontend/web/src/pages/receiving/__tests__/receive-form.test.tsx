import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import type { AsnLineResponse } from '@/types/receiving';

// ApiError needs a real shape so the over-receipt branch can read problem.type.
// Hoisted so the vi.mock factory (also hoisted) can reference it safely.
const { FakeApiError } = vi.hoisted(() => {
  class FakeApiError extends Error {
    problem: { type: string };
    constructor(type: string) {
      super('over receipt');
      this.problem = { type };
    }
  }
  return { FakeApiError };
});

vi.mock('@/lib/api-client', () => ({
  // The LocationPicker fetches /api/v1/locations; return one pickable location
  // so the over-receipt flow can satisfy the location requirement.
  api: {
    get: vi.fn(() =>
      Promise.resolve({
        content: [{ id: 7, name: 'RCV-01', area: { name: 'Receiving Area A' } }],
        page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
      }),
    ),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: FakeApiError,
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

const receiveMutate = vi.fn();
vi.mock('../use-receiving', () => ({
  useReceiveLine: vi.fn(() => ({ mutate: receiveMutate, isPending: false })),
}));

import { ReceiveForm } from '../receive-form';

const asnLine: AsnLineResponse = {
  id: 11,
  lineNumber: 1,
  itemDataId: 5,
  itemDataNumber: 'DEMO-MOUSE',
  expectedAmount: 100,
  receivedAmount: 80,
  remainingAmount: 20,
  progressPercent: 80,
  state: 500,
  stateName: 'STARTED',
  lotNumber: 'LOT-A',
};

function renderForm(prefillLine: AsnLineResponse | null, receiptType?: number) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ReceiveForm
        receiptId={1}
        prefill={{ asnLine: prefillLine }}
        disabled={false}
        onReceived={vi.fn()}
        {...(receiptType !== undefined ? { receiptType } : {})}
      />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  receiveMutate.mockReset();
});

describe('ReceiveForm', () => {
  it('locks the product and prefills the remaining amount + lot when bound to an ASN line', () => {
    renderForm(asnLine);
    expect(screen.getByTestId('receive-locked-product')).toHaveTextContent('DEMO-MOUSE');
    expect(screen.getByTestId('receive-amount')).toHaveValue(20);
  });

  it('blocks submit and shows an error when no location is picked', async () => {
    const user = userEvent.setup();
    renderForm(asnLine);

    await user.click(screen.getByTestId('receive-submit'));

    expect(screen.getByText(/pick a location/i)).toBeInTheDocument();
    expect(receiveMutate).not.toHaveBeenCalled();
  });

  it('sends QUALITY FAULT lock type when chosen from the lock select', async () => {
    const user = userEvent.setup();
    renderForm(asnLine);

    const locationInput = screen.getByPlaceholderText(/search location/i);
    await user.type(locationInput, 'RCV');
    await user.click(await screen.findByRole('option', { name: /RCV-01/i }));

    await user.selectOptions(screen.getByTestId('receive-lock-select'), '103');
    await user.click(screen.getByTestId('receive-submit'));

    expect(receiveMutate.mock.calls[0][0]).toMatchObject({ lockType: 103 });
  });

  it('sends the chosen lock type and note', async () => {
    const user = userEvent.setup();
    renderForm(asnLine);

    const locationInput = screen.getByPlaceholderText(/search location/i);
    await user.type(locationInput, 'RCV');
    await user.click(await screen.findByRole('option', { name: /RCV-01/i }));

    await user.selectOptions(screen.getByTestId('receive-lock-select'), '202');
    await user.type(screen.getByTestId('receive-note'), 'Damaged pallet corner');
    await user.click(screen.getByTestId('receive-submit'));

    expect(receiveMutate.mock.calls[0][0]).toMatchObject({
      lockType: 202,
      note: 'Damaged pallet corner',
    });
  });

  it('defaults the lock to QUALITY FAULT for RETOUR receipts but stays overridable', async () => {
    const user = userEvent.setup();
    renderForm(asnLine, 1);

    expect(screen.getByTestId('receive-lock-select')).toHaveValue('103');

    const locationInput = screen.getByPlaceholderText(/search location/i);
    await user.type(locationInput, 'RCV');
    await user.click(await screen.findByRole('option', { name: /RCV-01/i }));

    await user.selectOptions(screen.getByTestId('receive-lock-select'), '');
    await user.click(screen.getByTestId('receive-submit'));

    expect(receiveMutate.mock.calls[0][0]).toMatchObject({ lockType: undefined });
  });

  it('sends serial number and packaging unit when provided', async () => {
    const user = userEvent.setup();
    renderForm(asnLine);

    const locationInput = screen.getByPlaceholderText(/search location/i);
    await user.type(locationInput, 'RCV');
    await user.click(await screen.findByRole('option', { name: /RCV-01/i }));

    await user.type(screen.getByTestId('receive-serial'), 'SN-42');
    await user.type(screen.getByTestId('receive-packaging'), '9');
    await user.click(screen.getByTestId('receive-submit'));

    expect(receiveMutate.mock.calls[0][0]).toMatchObject({
      serialNumber: 'SN-42',
      packagingUnitId: 9,
    });
  });

  it('replaces the old QA switch', () => {
    renderForm(asnLine);
    expect(screen.queryByTestId('receive-qa-switch')).not.toBeInTheDocument();
  });

  it('shows an inline over-receipt confirm on 409 and retries with allowOverReceipt', async () => {
    const user = userEvent.setup();
    // First receive attempt -> over-receipt 409; the retry click -> success.
    receiveMutate
      .mockImplementationOnce((_vars, opts) => {
        opts.onError(new FakeApiError('https://karyo.com/errors/over-receipt'));
      })
      .mockImplementationOnce((_vars, opts) => {
        opts.onSuccess({ receipt: { id: 1 } });
      });

    renderForm(asnLine);

    // Pick the location via the searchable picker.
    const locationInput = screen.getByPlaceholderText(/search location/i);
    await user.type(locationInput, 'RCV');
    await user.click(await screen.findByRole('option', { name: /RCV-01/i }));

    // Over-receive: remaining is 20, enter 40.
    const amount = screen.getByTestId('receive-amount');
    await user.clear(amount);
    await user.type(amount, '40');

    await user.click(screen.getByTestId('receive-submit'));

    // Inline confirm appears (expected 100, already 80, +40 => exceeds by 20).
    const confirm = await screen.findByTestId('over-receipt-confirm');
    expect(confirm).toHaveTextContent(/exceeds by 20/i);

    // First call used allowOverReceipt=false.
    expect(receiveMutate.mock.calls[0][0]).toMatchObject({ allowOverReceipt: false });

    // Confirm -> retries with allowOverReceipt=true.
    await user.click(screen.getByTestId('over-receipt-confirm-button'));
    expect(receiveMutate.mock.calls[1][0]).toMatchObject({ allowOverReceipt: true });
  });

  it('cancels the over-receipt confirm without retrying', async () => {
    const user = userEvent.setup();
    receiveMutate.mockImplementationOnce((_vars, opts) => {
      opts.onError(new FakeApiError('https://karyo.com/errors/over-receipt'));
    });

    renderForm(asnLine);

    const locationInput = screen.getByPlaceholderText(/search location/i);
    await user.type(locationInput, 'RCV');
    await user.click(await screen.findByRole('option', { name: /RCV-01/i }));

    const amount = screen.getByTestId('receive-amount');
    await user.clear(amount);
    await user.type(amount, '40');
    await user.click(screen.getByTestId('receive-submit'));

    await screen.findByTestId('over-receipt-confirm');
    await user.click(screen.getByTestId('over-receipt-cancel'));

    expect(screen.queryByTestId('over-receipt-confirm')).not.toBeInTheDocument();
    expect(receiveMutate).toHaveBeenCalledTimes(1);
  });
});
