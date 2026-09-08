import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CountEntryView } from '@/types/cycle-count';

const submitMutate = vi.fn();
const unitLoadMissingMutate = vi.fn();
const locationEmptyMutate = vi.fn();

vi.mock('./use-cycle-count', () => ({
  useCountOrderEntry: vi.fn(),
  useSubmitCount: vi.fn(),
  useUnitLoadMissing: vi.fn(),
  useLocationEmpty: vi.fn(),
}));

import { useCountOrderEntry, useSubmitCount, useUnitLoadMissing, useLocationEmpty } from './use-cycle-count';
import { CountEntryForm } from './count-entry-form';

// Two unit loads: UL A has one still-open line; UL B's only line is already `counted: true`
// (e.g. zeroed by an earlier missing-op call) -- its group must render with NO missing button
// (nothing left to report) while UL A's group keeps its button.
const entry: CountEntryView = {
  id: 42,
  orderNumber: 'CO-0042',
  locationName: 'R-01-01',
  lines: [
    {
      lineId: 1,
      itemDataNumber: 'SKU-A',
      lotNumber: null,
      serialNumber: null,
      unitLoadId: 10,
      unitLoadLabel: 'UL-A',
      counted: false,
    },
    {
      lineId: 2,
      itemDataNumber: 'SKU-B',
      lotNumber: null,
      serialNumber: null,
      unitLoadId: 20,
      unitLoadLabel: 'UL-B',
      counted: true,
    },
  ],
};

const zeroLineEntry: CountEntryView = {
  id: 43,
  orderNumber: 'CO-0043',
  locationName: 'R-02-01',
  lines: [],
};

beforeEach(() => {
  submitMutate.mockClear();
  unitLoadMissingMutate.mockClear();
  locationEmptyMutate.mockClear();

  vi.mocked(useCountOrderEntry).mockReturnValue({
    data: entry,
    isLoading: false,
  } as ReturnType<typeof useCountOrderEntry>);

  vi.mocked(useSubmitCount).mockReturnValue({
    mutate: submitMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useSubmitCount>);

  vi.mocked(useUnitLoadMissing).mockReturnValue({
    mutate: unitLoadMissingMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useUnitLoadMissing>);

  vi.mocked(useLocationEmpty).mockReturnValue({
    mutate: locationEmptyMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useLocationEmpty>);
});

describe('CountEntryForm', () => {
  it('groups by unit load, shows one missing button, locks the counted line, dialog confirms the mutation, and submit omits the counted line', async () => {
    const user = userEvent.setup();
    render(<CountEntryForm orderId={42} onDone={vi.fn()} />);

    // two groups render
    expect(screen.getByText('UL-A')).toBeInTheDocument();
    expect(screen.getByText('UL-B')).toBeInTheDocument();

    // exactly one missing button in the whole form -- UL-A (open line, non-null unitLoadId);
    // UL-B has no open lines left, so its button is hidden even though its unitLoadId is
    // non-null too
    expect(screen.getByTestId('unit-load-missing-10')).toBeInTheDocument();
    expect(screen.queryByTestId('unit-load-missing-20')).not.toBeInTheDocument();
    expect(screen.getAllByText('Unit load missing')).toHaveLength(1);

    // the counted line's input is locked -- disabled, shows 0
    const lockedInput = screen.getByTestId('qty-input-2') as HTMLInputElement;
    expect(lockedInput).toBeDisabled();
    expect(lockedInput.value).toBe('0');

    // dialog confirm -> mutate(unitLoadId)
    await user.click(screen.getByTestId('unit-load-missing-10'));
    expect(screen.getByText('Report this unit load missing?')).toBeInTheDocument();
    await user.click(screen.getByTestId('confirm-unit-load-missing'));
    expect(unitLoadMissingMutate).toHaveBeenCalledWith(10, expect.anything());

    // submit omits the counted line (lineId 2) from the payload
    const openInput = screen.getByTestId('qty-input-1');
    await user.type(openInput, '5');
    await user.click(screen.getByTestId('submit-count-button'));
    expect(submitMutate).toHaveBeenCalledWith(
      { lines: [{ lineId: 1, countedAmount: 5 }] },
      expect.anything(),
    );
  });

  it('St3: "Location is empty" button confirms via dialog then calls useLocationEmpty with no args', async () => {
    const user = userEvent.setup();
    render(<CountEntryForm orderId={42} onDone={vi.fn()} />);

    await user.click(screen.getByTestId('location-empty-button'));
    expect(screen.getByText('Confirm the location is empty?')).toBeInTheDocument();
    await user.click(screen.getByTestId('confirm-location-empty'));
    expect(locationEmptyMutate).toHaveBeenCalledWith(undefined, expect.anything());
  });

  it('St3: a zero-line order renders an empty-state (no table, no Submit button) with the Location is empty button', async () => {
    vi.mocked(useCountOrderEntry).mockReturnValue({
      data: zeroLineEntry,
      isLoading: false,
    } as ReturnType<typeof useCountOrderEntry>);

    const user = userEvent.setup();
    render(<CountEntryForm orderId={43} onDone={vi.fn()} />);

    expect(screen.getByTestId('zero-line-empty-state')).toBeInTheDocument();
    expect(screen.queryByTestId('count-entry-table')).not.toBeInTheDocument();
    expect(screen.queryByTestId('submit-count-button')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('location-empty-button'));
    await user.click(screen.getByTestId('confirm-location-empty'));
    expect(locationEmptyMutate).toHaveBeenCalledWith(undefined, expect.anything());
  });
});
