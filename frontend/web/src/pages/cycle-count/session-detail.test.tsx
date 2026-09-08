import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import type { CountSessionView, CountEntryView } from '@/types/cycle-count';

vi.mock('@/lib/api-client', () => ({
  api: { get: vi.fn(), post: vi.fn() },
  ApiError: class ApiError extends Error {},
}));

const submitMutate = vi.fn();
const acceptMutate = vi.fn();
const recountMutate = vi.fn();
const cancelOrderMutate = vi.fn();

vi.mock('./use-cycle-count', () => ({
  useSession: vi.fn(),
  useCountOrder: vi.fn(),
  useCountOrderEntry: vi.fn(),
  useSubmitCount: vi.fn(),
  useUnitLoadMissing: vi.fn(),
  useLocationEmpty: vi.fn(),
  useAccept: vi.fn(),
  useRecount: vi.fn(),
  useCancelOrder: vi.fn(),
}));

import {
  useSession,
  useCountOrder,
  useCountOrderEntry,
  useSubmitCount,
  useUnitLoadMissing,
  useLocationEmpty,
  useAccept,
  useRecount,
  useCancelOrder,
} from './use-cycle-count';
import { SessionDetail } from './session-detail';

// Session with order 7 in "To count" state
const session: CountSessionView = {
  id: 2,
  sessionNumber: 'CS-0002',
  type: 'CYCLE',
  state: 100,
  orders: [
    { id: 7, orderNumber: 'CO-0007', sessionId: 2, locationId: 5, locationName: 'B-05', state: 50, lines: [] },
  ],
  campaignId: null,
};

const entryFor7: CountEntryView = {
  id: 7,
  orderNumber: 'CO-0007',
  locationName: 'B-05',
  lines: [
    {
      lineId: 1,
      itemDataNumber: 'SKU-1',
      lotNumber: null,
      serialNumber: null,
      unitLoadId: null,
      unitLoadLabel: null,
      counted: false,
    },
  ],
};

beforeEach(() => {
  submitMutate.mockClear();
  acceptMutate.mockClear();
  recountMutate.mockClear();
  cancelOrderMutate.mockClear();

  vi.mocked(useSession).mockReturnValue({
    data: session,
    isLoading: false,
  } as ReturnType<typeof useSession>);

  vi.mocked(useCountOrderEntry).mockImplementation(
    (id?: number) =>
      ({
        data: id === 7 ? entryFor7 : undefined,
        isLoading: false,
      }) as ReturnType<typeof useCountOrderEntry>,
  );

  vi.mocked(useCountOrder).mockReturnValue({
    data: undefined,
    isLoading: false,
  } as ReturnType<typeof useCountOrder>);

  vi.mocked(useSubmitCount).mockReturnValue({
    mutate: submitMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useSubmitCount>);

  vi.mocked(useUnitLoadMissing).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useUnitLoadMissing>);

  vi.mocked(useLocationEmpty).mockReturnValue({
    mutate: vi.fn(),
    isPending: false,
  } as unknown as ReturnType<typeof useLocationEmpty>);

  vi.mocked(useAccept).mockReturnValue({
    mutate: acceptMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useAccept>);

  vi.mocked(useRecount).mockReturnValue({
    mutate: recountMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useRecount>);

  vi.mocked(useCancelOrder).mockReturnValue({
    mutate: cancelOrderMutate,
    isPending: false,
  } as unknown as ReturnType<typeof useCancelOrder>);
});

describe('SessionDetail', () => {
  it('renders session info and order cards', () => {
    render(<SessionDetail sessionId={2} />);

    expect(screen.getByText('CS-0002')).toBeInTheDocument();
    expect(screen.getByText(/Count session/)).toBeInTheDocument();
    expect(screen.getByText('CO-0007')).toBeInTheDocument();
  });

  it('appliedDeepLinkRef prevents re-opening order after user closes and session refetch', async () => {
    const user = userEvent.setup();
    // Render with initialOrderId=7, which should auto-open the count entry
    const { rerender } = render(<SessionDetail sessionId={2} initialOrderId={7} />);

    // Deep link applies: order entry opens for order 7
    expect(await screen.findByTestId('count-entry-table')).toBeInTheDocument();

    // User clicks "← Back to session" to close the order
    await user.click(screen.getByText('← Back to session'));

    // Session view should be visible (order list), activeOrderId should be undefined
    expect(screen.getByTestId('order-row-7')).toBeInTheDocument();
    expect(screen.queryByTestId('count-entry-table')).not.toBeInTheDocument();

    // Simulate a session data refetch (new object reference but same data) which would cause
    // the dependency array to update. This tests that appliedDeepLinkRef prevents
    // the initialOrderId from re-applying and re-opening the order entry.
    vi.mocked(useSession).mockReturnValue({
      data: { ...session }, // new object reference
      isLoading: false,
    } as ReturnType<typeof useSession>);
    rerender(<SessionDetail sessionId={2} initialOrderId={7} />);

    // Without appliedDeepLinkRef guard, the order entry would re-open after the refetch.
    // With the guard, the session view should stay open (order list, not entry form).
    expect(screen.getByTestId('order-row-7')).toBeInTheDocument();
    expect(screen.queryByTestId('count-entry-table')).not.toBeInTheDocument();
  });

  it('St7: cancelling a GENERATED order row opens a confirm dialog and calls useCancelOrder on confirm', async () => {
    const user = userEvent.setup();
    render(<SessionDetail sessionId={2} />);

    // Cancel button on the order-50 row -- must not open the count entry (stopPropagation)
    await user.click(screen.getByTestId('order-cancel-7'));
    expect(screen.queryByTestId('count-entry-table')).not.toBeInTheDocument();

    // Confirm dialog appears
    expect(screen.getByText('Cancel this count order?')).toBeInTheDocument();
    expect(cancelOrderMutate).not.toHaveBeenCalled();

    // Confirming calls the mutation with the order id
    await user.click(screen.getByText('Cancel order'));
    expect(cancelOrderMutate).toHaveBeenCalledWith(7, expect.anything());
  });
});
