import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { StorageStrategyForm } from '../storage-strategy-form';
import type { StorageStrategyResponse } from '@/types/strategies';

// Radix Select needs these two DOM APIs, which jsdom does not implement.
Element.prototype.hasPointerCapture = Element.prototype.hasPointerCapture ?? (() => false);
Element.prototype.scrollIntoView = Element.prototype.scrollIntoView ?? (() => {});

const createMutate = vi.fn();
const updateMutate = vi.fn();

vi.mock('../use-strategies', () => ({
  useCreateStorageStrategy: vi.fn(() => ({ mutate: createMutate, isPending: false })),
  useUpdateStorageStrategy: vi.fn(() => ({ mutate: updateMutate, isPending: false })),
}));

const seededStrategy: StorageStrategyResponse = {
  id: 7,
  name: 'ZONE-A',
  zoneId: 3,
  mixItem: false,
  mixClient: true,
  nearPickingLocation: true,
  sorts: 'ALLOCATION,POSITION_X,legacy_garbage',
  onlyClientLocation: true,
  manualSearch: true,
  useAreaStrategyDate: true,
  useItemDataArea: true,
  created: '2026-07-01T00:00:00Z',
  modified: '2026-07-01T00:00:00Z',
};

beforeEach(() => {
  vi.clearAllMocks();
});

describe('StorageStrategyForm', () => {
  it('seeds all four V312 flags from the strategy when editing', () => {
    render(<StorageStrategyForm strategy={seededStrategy} onClose={vi.fn()} />);

    expect(screen.getByRole('switch', { name: /only client location/i })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: /manual search/i })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: /use area strategy date/i })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: /use item data area/i })).toHaveAttribute('aria-checked', 'true');
  });

  it('creates a new strategy with onlyClientLocation defaulting to true (owner-scoped by default) and the other three V312 flags defaulting to false', async () => {
    const user = userEvent.setup();
    render(<StorageStrategyForm onClose={vi.fn()} />);

    expect(screen.getByRole('switch', { name: /only client location/i })).toHaveAttribute('aria-checked', 'true');
    expect(screen.getByRole('switch', { name: /manual search/i })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('switch', { name: /use area strategy date/i })).toHaveAttribute('aria-checked', 'false');
    expect(screen.getByRole('switch', { name: /use item data area/i })).toHaveAttribute('aria-checked', 'false');

    await user.type(screen.getByLabelText(/^name$/i), 'NEW-STRAT');
    await user.click(screen.getByTestId('storage-strategy-submit'));

    expect(createMutate).toHaveBeenCalledTimes(1);
    const payload = createMutate.mock.calls[0][0];
    expect(payload).toMatchObject({
      name: 'NEW-STRAT',
      onlyClientLocation: true,
      manualSearch: false,
      useAreaStrategyDate: false,
      useItemDataArea: false,
    });
  });

  it('sends toggled V312 flags in the update payload', async () => {
    const user = userEvent.setup();
    render(<StorageStrategyForm strategy={seededStrategy} onClose={vi.fn()} />);

    // Flip manualSearch off, leave the other three (seeded true) untouched.
    await user.click(screen.getByRole('switch', { name: /manual search/i }));
    await user.click(screen.getByTestId('storage-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload).toMatchObject({
      id: 7,
      onlyClientLocation: true,
      manualSearch: false,
      useAreaStrategyDate: true,
      useItemDataArea: true,
    });
  });

  // ── Task 5: ordered sorts multi-select ──

  it('seeds the ordered chip list from sorts, dropping an unrecognized legacy token', () => {
    render(<StorageStrategyForm strategy={seededStrategy} onClose={vi.fn()} />);

    // 'legacy_garbage' is not one of the 9 typed values -- silently dropped, mirroring
    // the backend parser's read-time skip-unknown behavior.
    const chips = screen.getAllByTestId(/storage-strategy-sort-chip-/);
    expect(chips).toHaveLength(2);
    expect(chips[0]).toHaveTextContent(/allocation/i);
    expect(chips[1]).toHaveTextContent(/position x/i);
  });

  it('adds a sort from the select, reorders it up, and serializes to the CSV payload', async () => {
    const user = userEvent.setup();
    render(<StorageStrategyForm onClose={vi.fn()} />);

    await user.type(screen.getByLabelText(/^name$/i), 'SORT-TEST');

    // No sorts yet -- add CAPACITY then NAME via the select.
    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByText(/^capacity order$/i));
    await user.click(screen.getByRole('combobox'));
    await user.click(await screen.findByText(/^location name$/i));

    let chips = await screen.findAllByTestId(/storage-strategy-sort-chip-/);
    expect(chips[0]).toHaveTextContent(/capacity order/i);
    expect(chips[1]).toHaveTextContent(/location name/i);

    // Move the second chip (NAME) up so it outranks CAPACITY.
    await user.click(screen.getByRole('button', { name: /move location name up/i }));
    chips = await screen.findAllByTestId(/storage-strategy-sort-chip-/);
    expect(chips[0]).toHaveTextContent(/location name/i);
    expect(chips[1]).toHaveTextContent(/capacity order/i);

    await user.click(screen.getByTestId('storage-strategy-submit'));

    expect(createMutate).toHaveBeenCalledTimes(1);
    const payload = createMutate.mock.calls[0][0];
    expect(payload.sorts).toBe('NAME,CAPACITY');
  });

  it('removing every chip serializes sorts as undefined (omitted), not an empty string', async () => {
    const user = userEvent.setup();
    render(<StorageStrategyForm strategy={seededStrategy} onClose={vi.fn()} />);

    await user.click(screen.getByRole('button', { name: /remove allocation/i }));
    await user.click(screen.getByRole('button', { name: /remove position x/i }));
    expect(screen.queryAllByTestId(/storage-strategy-sort-chip-/)).toHaveLength(0);

    await user.click(screen.getByTestId('storage-strategy-submit'));

    expect(updateMutate).toHaveBeenCalledTimes(1);
    const payload = updateMutate.mock.calls[0][0];
    expect(payload.sorts).toBeUndefined();
  });
});
