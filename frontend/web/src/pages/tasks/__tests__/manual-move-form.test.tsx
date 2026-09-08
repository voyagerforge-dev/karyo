import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';

// LocationPicker + unit-loads both use api.get; route by URL.
vi.mock('@/lib/api-client', () => ({
  api: {
    get: vi.fn((url: string) => {
      if (url.includes('/unit-loads')) {
        return Promise.resolve([
          { id: 11, labelId: 'UL-XYZ', storageLocationName: 'STR-01' },
        ]);
      }
      // locations picker
      return Promise.resolve({
        content: [
          { id: 5, name: 'STR-01', area: { name: 'Storage A' } },
          { id: 6, name: 'STR-02', area: { name: 'Storage A' } },
        ],
        page: { number: 0, size: 200, totalElements: 2, totalPages: 1 },
      });
    }),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
  ApiError: class ApiError extends Error {},
}));

vi.mock('sonner', () => ({
  toast: { success: vi.fn(), error: vi.fn(), warning: vi.fn(), dismiss: vi.fn() },
}));

const createMoveMutate = vi.fn();
vi.mock('../use-tasks', () => ({
  useCreateManualMove: vi.fn(() => ({ mutate: createMoveMutate, isPending: false })),
}));

import { ManualMoveForm } from '../manual-move-form';

function renderForm() {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <ManualMoveForm onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  createMoveMutate.mockReset();
});

describe('ManualMoveForm', () => {
  it('validates that source, unit load, and destination are all required', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.click(screen.getByTestId('manual-move-submit'));

    expect(screen.getAllByText(/pick a source location/i).length).toBeGreaterThan(0);
    expect(createMoveMutate).not.toHaveBeenCalled();
  });

  it('creates a MOVE once source, unit load, and destination are chosen', async () => {
    const user = userEvent.setup();
    renderForm();

    // Source location -> STR-01 (first location picker on the form).
    const inputs = screen.getAllByPlaceholderText(/search location/i);
    await user.type(inputs[0], 'STR-01');
    await user.click(await screen.findByRole('option', { name: /STR-01/i }));

    // Unit load select appears once the source is set.
    const ulSelect = await screen.findByTestId('move-unit-load-select');
    await user.selectOptions(ulSelect, '11');

    // Destination -> STR-02 (the destination location picker).
    const destInputs = screen.getAllByPlaceholderText(/search location/i);
    await user.type(destInputs[destInputs.length - 1], 'STR-02');
    await user.click(await screen.findByRole('option', { name: /STR-02/i }));

    await user.click(screen.getByTestId('manual-move-submit'));

    expect(createMoveMutate).toHaveBeenCalledTimes(1);
    expect(createMoveMutate.mock.calls[0][0]).toMatchObject({
      unitLoadId: 11,
      destinationLocationId: 6,
      destinationLocationName: 'STR-02',
    });
    // No ERP refs entered -- neither field is sent (optional, folded in only
    // when non-empty).
    expect(createMoveMutate.mock.calls[0][0]).not.toHaveProperty('externalNumber');
    expect(createMoveMutate.mock.calls[0][0]).not.toHaveProperty('externalId');
  });

  // PT17: optional ERP reference pair, threaded only through this manual-move path.
  it('folds non-empty external ref inputs into the create payload', async () => {
    const user = userEvent.setup();
    renderForm();

    const inputs = screen.getAllByPlaceholderText(/search location/i);
    await user.type(inputs[0], 'STR-01');
    await user.click(await screen.findByRole('option', { name: /STR-01/i }));

    const ulSelect = await screen.findByTestId('move-unit-load-select');
    await user.selectOptions(ulSelect, '11');

    const destInputs = screen.getAllByPlaceholderText(/search location/i);
    await user.type(destInputs[destInputs.length - 1], 'STR-02');
    await user.click(await screen.findByRole('option', { name: /STR-02/i }));

    await user.type(screen.getByTestId('move-external-number-input'), 'PO-9001');
    await user.type(screen.getByTestId('move-external-id-input'), 'erp-ref-42');

    await user.click(screen.getByTestId('manual-move-submit'));

    expect(createMoveMutate.mock.calls[0][0]).toMatchObject({
      unitLoadId: 11,
      destinationLocationId: 6,
      destinationLocationName: 'STR-02',
      externalNumber: 'PO-9001',
      externalId: 'erp-ref-42',
    });
  });
});
