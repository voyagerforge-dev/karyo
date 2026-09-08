import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { RuleBuilder } from './rule-builder';
import type { Monitor, MonitorsController } from './use-monitors';

const BASE_MONITOR: Monitor = {
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
  ch: { push: false, email: false, slack: false },
  fired: '',
  fix: '',
  alertId: null,
};

function makeController(overrides: Partial<Monitor> = {}): MonitorsController {
  const selected = { ...BASE_MONITOR, ...overrides };
  return {
    clock: '00:00:00',
    monitors: [selected],
    firing: [],
    selected,
    activeCount: 1,
    firingCount: 0,
    mutedCount: 0,
    select: vi.fn(),
    toggleEnabled: vi.fn(),
    applyFix: vi.fn(),
    mute: vi.fn(),
    setOp: vi.fn(),
    stepThreshold: vi.fn(),
    setSeverity: vi.fn(),
    toggleChannel: vi.fn(),
  };
}

describe('RuleBuilder — channel toggles', () => {
  it('does not render a push toggle', () => {
    render(<RuleBuilder controller={makeController()} />);
    expect(screen.queryByRole('button', { name: /push/i })).not.toBeInTheDocument();
  });

  it('renders email and slack toggles', () => {
    render(<RuleBuilder controller={makeController()} />);
    expect(screen.getByRole('button', { name: /email/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /slack/i })).toBeInTheDocument();
  });

  it('clicking the email toggle calls controller.toggleChannel("email")', async () => {
    const controller = makeController();
    render(<RuleBuilder controller={controller} />);
    await userEvent.click(screen.getByRole('button', { name: /email/i }));
    expect(controller.toggleChannel).toHaveBeenCalledWith('email');
  });

  it('shows no recipients hint when email and slack are both off', () => {
    render(<RuleBuilder controller={makeController({ ch: { push: false, email: false, slack: false } })} />);
    expect(screen.queryByText(/Recipients come from Admin/)).not.toBeInTheDocument();
  });

  it('shows the recipients hint when email is on', () => {
    render(<RuleBuilder controller={makeController({ ch: { push: false, email: true, slack: false } })} />);
    expect(screen.getByText(/Recipients come from Admin.*System properties/)).toBeInTheDocument();
  });

  it('shows the recipients hint when slack is on', () => {
    render(<RuleBuilder controller={makeController({ ch: { push: false, email: false, slack: true } })} />);
    expect(screen.getByText(/Recipients come from Admin.*System properties/)).toBeInTheDocument();
  });
});
