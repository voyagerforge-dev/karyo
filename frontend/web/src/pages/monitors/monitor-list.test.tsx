import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MonitorList } from './monitor-list';
import type { Monitor } from './use-monitors';

const MONITOR: Monitor = {
  id: 'stuck-order',
  name: 'Stuck order',
  metric: 'Order idle in a non-terminal state',
  op: '>',
  threshold: 24,
  unit: 'h',
  step: 1,
  scope: 'Fulfillment',
  severity: 'High',
  enabled: true,
  status: 'healthy',
  last: '—',
  ch: { push: true, email: true, slack: false },
  fired: '',
  fix: '',
  alertId: null,
};

describe('MonitorList — channel indicators', () => {
  it('renders no push indicator, only email and slack', () => {
    const { container } = render(
      <MonitorList monitors={[MONITOR]} selectedId="stuck-order" onSelect={vi.fn()} onToggle={vi.fn()} />,
    );
    // Only 2 channel icons per row now (email, slack) — push (Bell) dropped.
    const row = screen.getByRole('button', { name: /Stuck order/i });
    const icons = row.querySelectorAll('svg');
    // 2 channel icons; row has no other svgs in this component.
    expect(icons).toHaveLength(2);
    expect(container.querySelector('[data-testid="channel-push"]')).not.toBeInTheDocument();
  });
});
