import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import userEvent from '@testing-library/user-event';
import { OrderStrategyForm } from '../order-strategy-form';
import type { OrderStrategyResponse } from '@/types/strategies';

// Radix Select needs these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

const createMutate = vi.fn();
const updateMutate = vi.fn();

vi.mock('../use-strategies', () => ({
  useCreateOrderStrategy: vi.fn(() => ({ mutate: createMutate, isPending: false })),
  useUpdateOrderStrategy: vi.fn(() => ({ mutate: updateMutate, isPending: false })),
}));

const mockIsEntitled = vi.fn(() => false);
vi.mock('@/features/license/use-license', () => ({
  useLicense: () => ({ isLoading: false, isEntitled: mockIsEntitled }),
}));

// Row 8: the "Default destination" field renders LocationPicker, which fetches
// /api/v1/locations -- give it one pickable location.
vi.mock('@/lib/api-client', () => ({
  api: {
    get: vi.fn(() =>
      Promise.resolve({
        content: [{ id: 9, name: 'DEST-01' }],
        page: { number: 0, size: 200, totalElements: 1, totalPages: 1 },
      }),
    ),
    post: vi.fn(),
    put: vi.fn(),
    delete: vi.fn(),
  },
}));

const seededStrategy: OrderStrategyResponse = {
  id: 5,
  name: 'ECOMM-FAST',
  useLockedStock: true,
  preferComplete: false,
  preferMatching: true,
  completeHandling: 2,
  enforceLot: true,
  shortPickMode: 'SUBSTITUTE_ONLY',
  shortfallStrategy: 'CUSTOM_SHORTFALL',
  pickDifferenceStrategy: 'CUSTOM_DIFF',
  packoutStrategy: 'CUSTOM_PACKOUT',
  extensionProperties: { foo: 'bar' },
  sendToPacking: true,
  sendToShipping: false,
  createShippingOrder: true,
  createTypeOrders: false,
  defaultDestinationLocationId: 9,
  defaultDestinationLocationName: 'DEST-01',
};

function renderForm(strategy?: OrderStrategyResponse) {
  const queryClient = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  return render(
    <QueryClientProvider client={queryClient}>
      <OrderStrategyForm strategy={strategy} onClose={vi.fn()} />
    </QueryClientProvider>,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  mockIsEntitled.mockReturnValue(false);
});

describe('OrderStrategyForm', () => {
  it('seeds every new field from the strategy when editing', () => {
    renderForm(seededStrategy);

    expect(screen.getByRole('switch', { name: /use locked stock/i })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByRole('switch', { name: /prefer complete/i })).toHaveAttribute(
      'aria-checked',
      'false',
    );
    expect(screen.getByRole('switch', { name: /prefer matching/i })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByRole('switch', { name: /enforce lot/i })).toHaveAttribute(
      'aria-checked',
      'true',
    );

    expect(screen.getByRole('combobox', { name: /short pick mode/i })).toHaveTextContent(
      /substitute only/i,
    );

    expect(screen.getByLabelText(/complete handling/i)).toHaveValue(2);
    expect(screen.getByLabelText(/shortfall strategy/i)).toHaveValue('CUSTOM_SHORTFALL');
    expect(screen.getByLabelText(/pick difference strategy/i)).toHaveValue('CUSTOM_DIFF');
    expect(screen.getByLabelText(/packout strategy/i)).toHaveValue('CUSTOM_PACKOUT');

    // Row 8
    expect(screen.getByRole('switch', { name: /send to packing/i })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByRole('switch', { name: /send to shipping/i })).toHaveAttribute(
      'aria-checked',
      'false',
    );
    expect(screen.getByRole('switch', { name: /auto-open shipment/i })).toHaveAttribute(
      'aria-checked',
      'true',
    );
    expect(screen.getByRole('switch', { name: /split by picking type/i })).toHaveAttribute(
      'aria-checked',
      'false',
    );
    expect(screen.getByText('DEST-01')).toBeInTheDocument();
  });

  it('sends every new field in the update payload, including a changed shortPickMode', async () => {
    const user = userEvent.setup();
    renderForm(seededStrategy);

    await user.click(screen.getByRole('combobox', { name: /short pick mode/i }));
    await user.click(await screen.findByText(/^none$/i));

    await user.click(screen.getByTestId('order-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload).toMatchObject({
      id: 5,
      useLockedStock: true,
      preferComplete: false,
      preferMatching: true,
      completeHandling: 2,
      enforceLot: true,
      shortPickMode: 'NONE',
      shortfallStrategy: 'CUSTOM_SHORTFALL',
      pickDifferenceStrategy: 'CUSTOM_DIFF',
      packoutStrategy: 'CUSTOM_PACKOUT',
      extensionProperties: { foo: 'bar' },
      sendToPacking: true,
      sendToShipping: false,
      createShippingOrder: true,
      createTypeOrders: false,
      defaultDestinationLocationId: 9,
    });
  });

  it('creates a new strategy with the documented defaults', async () => {
    const user = userEvent.setup();
    renderForm();

    await user.type(screen.getByLabelText(/^name$/i), 'NEW-STRAT');
    await user.click(screen.getByTestId('order-strategy-submit'));

    expect(createMutate).toHaveBeenCalledTimes(1);
    const payload = createMutate.mock.calls[0][0];
    expect(payload).toMatchObject({
      name: 'NEW-STRAT',
      useLockedStock: false,
      preferComplete: true,
      preferMatching: false,
      completeHandling: 0,
      enforceLot: false,
      shortPickMode: 'FOLLOW_UP_THEN_SUBSTITUTE',
      shortfallStrategy: 'PARTIAL_SHIP',
      pickDifferenceStrategy: 'LEAVE',
      packoutStrategy: 'ONE_TO_ONE',
      extensionProperties: {},
      sendToPacking: false,
      sendToShipping: false,
      createShippingOrder: false,
      createTypeOrders: false,
    });
    expect(payload.defaultDestinationLocationId).toBeUndefined();
  });

  it('clears defaultDestinationLocationId when the picker is cleared (:1457 explicit null)', async () => {
    const user = userEvent.setup();
    renderForm(seededStrategy);

    await user.click(screen.getByRole('button', { name: /clear location/i }));
    await user.click(screen.getByTestId('order-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload).toMatchObject({ id: 5, defaultDestinationLocationId: null });
  });

  it('omits defaultDestinationLocationId when it was never set and stays untouched', async () => {
    const untouchedStrategy: OrderStrategyResponse = {
      ...seededStrategy,
      defaultDestinationLocationId: null,
      defaultDestinationLocationName: null,
    };
    const user = userEvent.setup();
    renderForm(untouchedStrategy);

    await user.click(screen.getByTestId('order-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload.defaultDestinationLocationId).toBeUndefined();
  });

  it('shows a lock badge on CARTONIZATION when the cartonization entitlement is missing', async () => {
    mockIsEntitled.mockReturnValue(false);
    const user = userEvent.setup();
    renderForm();

    const packoutInput = screen.getByLabelText(/packout strategy/i);
    await user.clear(packoutInput);
    await user.type(packoutInput, 'CARTONIZATION');

    expect(screen.getByTestId('packout-cartonization-locked')).toBeInTheDocument();
  });

  it('hides the lock badge on CARTONIZATION once the cartonization entitlement is present', async () => {
    mockIsEntitled.mockReturnValue(true);
    const user = userEvent.setup();
    renderForm();

    const packoutInput = screen.getByLabelText(/packout strategy/i);
    await user.clear(packoutInput);
    await user.type(packoutInput, 'CARTONIZATION');

    expect(screen.queryByTestId('packout-cartonization-locked')).not.toBeInTheDocument();
  });

  it('does not show the lock badge for the built-in ONE_TO_ONE strategy', () => {
    renderForm();

    expect(screen.queryByTestId('packout-cartonization-locked')).not.toBeInTheDocument();
  });
});

describe('OrderStrategyForm -- Task 8 release mode + streaming knobs', () => {
  const streamStrategy: OrderStrategyResponse = {
    ...seededStrategy,
    extensionProperties: { releaseMode: 'STREAM', streamBatchSize: 7, foo: 'bar' },
  };

  it('seeds the release mode select + stream knobs, stripping the five keys from the ext JSON textarea', () => {
    renderForm(streamStrategy);

    expect(screen.getByRole('combobox', { name: /release mode/i })).toHaveTextContent(/^stream$/i);
    expect(screen.getByLabelText(/stream batch size/i)).toHaveValue(7);
    expect(screen.getByLabelText(/stream max wait/i)).toHaveValue(30);
    expect(screen.getByLabelText(/stream abandon/i)).toHaveValue(1800);
    expect(screen.getByLabelText(/stream timing strategy/i)).toHaveValue('time-size');

    const textarea = screen.getByLabelText(/extension properties/i);
    const seeded = JSON.parse((textarea as HTMLTextAreaElement).value);
    expect(seeded).toEqual({ foo: 'bar' });
  });

  it('accepts a lowercase API-written releaseMode -- the backend parser is case-insensitive', () => {
    renderForm({ ...seededStrategy, extensionProperties: { releaseMode: 'stream', foo: 'bar' } });

    expect(screen.getByRole('combobox', { name: /release mode/i })).toHaveTextContent(/^stream$/i);
    expect(screen.getByLabelText(/stream batch size/i)).toBeInTheDocument();
  });

  it('defaults release mode to MANUAL and hides the stream knobs when the strategy has none', () => {
    renderForm(seededStrategy);

    expect(screen.getByRole('combobox', { name: /release mode/i })).toHaveTextContent(/^manual$/i);
    expect(screen.queryByLabelText(/stream batch size/i)).not.toBeInTheDocument();
  });

  it('merges the typed release fields with the free-form JSON on submit when STREAM is selected', async () => {
    const user = userEvent.setup();
    renderForm(streamStrategy);

    await user.clear(screen.getByLabelText(/stream abandon/i));
    await user.type(screen.getByLabelText(/stream abandon/i), '900');

    await user.click(screen.getByTestId('order-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload.extensionProperties).toEqual({
      foo: 'bar',
      releaseMode: 'STREAM',
      streamBatchSize: 7,
      streamMaxWaitSeconds: 30,
      streamAbandonSeconds: 900,
      streamTimingStrategy: 'time-size',
    });
  });

  it('sends releaseMode MANUAL and no stream keys when MANUAL is selected', async () => {
    const user = userEvent.setup();
    renderForm(seededStrategy);

    await user.click(screen.getByTestId('order-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload.extensionProperties).toEqual({ foo: 'bar', releaseMode: 'MANUAL' });
  });

  it('falls back to the documented defaults (30, 1800) when max-wait/abandon are cleared, not 0', async () => {
    const user = userEvent.setup();
    renderForm(streamStrategy);

    await user.clear(screen.getByLabelText(/stream max wait/i));
    await user.clear(screen.getByLabelText(/stream abandon/i));

    await user.click(screen.getByTestId('order-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload.extensionProperties).toMatchObject({
      streamMaxWaitSeconds: 30,
      streamAbandonSeconds: 1800,
    });
  });

  it('preserves an explicit 0 for max-wait/abandon (forces immediate escalation/stall)', async () => {
    const user = userEvent.setup();
    renderForm(streamStrategy);

    await user.clear(screen.getByLabelText(/stream max wait/i));
    await user.type(screen.getByLabelText(/stream max wait/i), '0');
    await user.clear(screen.getByLabelText(/stream abandon/i));
    await user.type(screen.getByLabelText(/stream abandon/i), '0');

    await user.click(screen.getByTestId('order-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload.extensionProperties).toMatchObject({
      streamMaxWaitSeconds: 0,
      streamAbandonSeconds: 0,
    });
  });

  it('shows stream-mode-locked when STREAM is selected without the advanced-fulfillment entitlement', () => {
    mockIsEntitled.mockReturnValue(false);
    renderForm(streamStrategy);

    expect(screen.getByTestId('stream-mode-locked')).toBeInTheDocument();
  });

  it('hides stream-mode-locked once advanced-fulfillment is entitled, and does not block MANUAL', () => {
    mockIsEntitled.mockReturnValue(true);
    const r = renderForm(streamStrategy);
    expect(screen.queryByTestId('stream-mode-locked')).not.toBeInTheDocument();
    r.unmount();

    mockIsEntitled.mockReturnValue(false);
    renderForm(seededStrategy);
    expect(screen.queryByTestId('stream-mode-locked')).not.toBeInTheDocument();
  });
});
