import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter } from 'react-router';
import type { GoodsReceiptResponse } from '@/types/receiving';
import type { WorkItemResponse } from '@/types/work';

const receipt: GoodsReceiptResponse = {
  id: 12,
  receiptNumber: 'GR-2026-0012',
  asns: [{ id: 1, asnNumber: 'ASN-0001' }],
  carrierName: null,
  deliveryNoteNumber: null,
  notes: null,
  receiptType: 0,
  prio: 50,
  receiptDate: null,
  dockLocationId: 5,
  dockLocationName: 'DOCK-A',
  operatorId: null,
  pausedAt: null,
  state: 50,
  stateName: 'CREATED',
  clientId: 1,
  lines: [],
  created: '2026-06-13T09:10:00Z',
  modified: '2026-06-13T09:10:00Z',
};

let currentReceipt: GoodsReceiptResponse | undefined = receipt;

vi.mock('@/pages/receiving/use-receiving', () => ({
  useGoodsReceipt: vi.fn(() => ({ data: currentReceipt, isLoading: false })),
}));

import { ReceivePane } from '../receive-pane';

const workItem: WorkItemResponse = {
  ref: 'RECEIVE:12',
  workType: 'RECEIVE',
  priority: 50,
  state: 'OPEN',
  claimedBy: null,
  zone: null,
  primaryLocation: 'DOCK-A',
  destination: null,
  summary: 'Receive GR-2026-0012 @ DOCK-A',
  createdAt: '2026-06-13T09:10:00Z',
};

function renderPane() {
  return render(
    <MemoryRouter>
      <ReceivePane workItem={workItem} />
    </MemoryRouter>,
  );
}

describe('ReceivePane', () => {
  it('shows the receipt summary and deep-links to the receiving workbench', () => {
    currentReceipt = receipt;
    renderPane();

    expect(screen.getByText('GR-2026-0012')).toBeInTheDocument();
    expect(screen.getByText('DOCK-A')).toBeInTheDocument();
    expect(screen.getByText('CREATED')).toBeInTheDocument();
    expect(screen.getByText('1')).toBeInTheDocument(); // linked ASN count

    const link = screen.getByTestId('receive-open-workbench');
    expect(link).toHaveAttribute('href', '/receiving/12');
  });

  it('renders skeletons while the receipt is loading', () => {
    currentReceipt = undefined;
    renderPane();

    expect(screen.queryByTestId('receive-open-workbench')).not.toBeInTheDocument();
  });
});
