import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { AlertDeliveriesPanel } from './alert-deliveries-panel';

const mockMutate = vi.fn();
const mockUseAlertDeliveries = vi.fn();

vi.mock('@/pages/monitors/use-alert-deliveries', () => ({
  useAlertDeliveries: (...args: unknown[]) => mockUseAlertDeliveries(...args),
  useRedeliverAlertDelivery: () => ({ mutate: mockMutate }),
}));

const DEAD_ROW = {
  id: 7,
  alertId: 42,
  channelKey: 'email',
  status: 'DEAD',
  attempts: 8,
  nextAttemptAt: new Date().toISOString(),
  lastError: 'no email recipients configured',
  created: new Date().toISOString(),
};

const DELIVERED_ROW = {
  id: 8,
  alertId: 43,
  channelKey: 'slack',
  status: 'DELIVERED',
  attempts: 1,
  nextAttemptAt: new Date().toISOString(),
  lastError: null,
  created: new Date().toISOString(),
};

describe('AlertDeliveriesPanel', () => {
  it('renders a status chip and redeliver button for a DEAD row', () => {
    mockUseAlertDeliveries.mockReturnValue({ data: [DEAD_ROW], isLoading: false });
    render(<AlertDeliveriesPanel />);

    expect(screen.getByText('DEAD')).toBeInTheDocument();
    expect(screen.getByText('email')).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /redeliver/i })).toBeInTheDocument();
  });

  it('does not render a redeliver button for a DELIVERED row', () => {
    mockUseAlertDeliveries.mockReturnValue({ data: [DELIVERED_ROW], isLoading: false });
    render(<AlertDeliveriesPanel />);

    expect(screen.getByText('DELIVERED')).toBeInTheDocument();
    expect(screen.queryByRole('button', { name: /redeliver/i })).not.toBeInTheDocument();
  });

  it('clicking redeliver calls the mutation with the row id', async () => {
    mockUseAlertDeliveries.mockReturnValue({ data: [DEAD_ROW], isLoading: false });
    render(<AlertDeliveriesPanel />);

    await userEvent.click(screen.getByRole('button', { name: /redeliver/i }));
    expect(mockMutate).toHaveBeenCalledWith(7, expect.anything());
  });

  it('shows an empty state when there are no deliveries', () => {
    mockUseAlertDeliveries.mockReturnValue({ data: [], isLoading: false });
    render(<AlertDeliveriesPanel />);

    expect(screen.getByText('No deliveries yet.')).toBeInTheDocument();
  });
});
