import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { PickConfirmForm } from '../pick-confirm-form';

describe('PickConfirmForm', () => {
  it('defaults the qty to the planned amount and confirms full', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(<PickConfirmForm plannedAmount={60} onConfirm={onConfirm} onCancel={() => {}} />);
    await user.click(screen.getByRole('button', { name: /confirm pick/i }));
    expect(onConfirm).toHaveBeenCalledWith(60, undefined);
  });

  it('rejects 0 and amounts over planned', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(<PickConfirmForm plannedAmount={60} onConfirm={onConfirm} onCancel={() => {}} />);
    const qty = screen.getByLabelText(/picked qty/i);
    await user.clear(qty); await user.type(qty, '0');
    await user.click(screen.getByRole('button', { name: /confirm pick/i }));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByText(/between 0 and 60/i)).toBeInTheDocument();

    await user.clear(qty); await user.type(qty, '99');
    await user.click(screen.getByRole('button', { name: /confirm pick/i }));
    expect(onConfirm).not.toHaveBeenCalled();
  });

  it('confirms a short amount', async () => {
    const user = userEvent.setup();
    const onConfirm = vi.fn();
    render(<PickConfirmForm plannedAmount={60} onConfirm={onConfirm} onCancel={() => {}} />);
    const qty = screen.getByLabelText(/picked qty/i);
    await user.clear(qty); await user.type(qty, '50');
    await user.click(screen.getByRole('button', { name: /confirm pick/i }));
    expect(onConfirm).toHaveBeenCalledWith(50, undefined);
  });
});
