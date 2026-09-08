import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PackForm } from '../pack-form';

describe('PackForm', () => {
  it('shows helper text clarifying weight is per-call, not the shipment total', () => {
    render(<PackForm isPending={false} onConfirm={() => {}} onCancel={() => {}} />);
    expect(
      screen.getByText(/weight of what is being packed in this call, not the shipment total/i),
    ).toBeInTheDocument();
  });

  it('blocks a non-positive weight and does not call onConfirm', () => {
    const onConfirm = vi.fn();
    render(<PackForm isPending={false} onConfirm={onConfirm} onCancel={() => {}} />);
    fireEvent.change(screen.getByLabelText(/weight/i), { target: { value: '0' } });
    fireEvent.click(screen.getByRole('button', { name: /confirm pack/i }));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByText(/weight must be greater than 0/i)).toBeInTheDocument();
  });

  it('submits weight + carton type', () => {
    const onConfirm = vi.fn();
    render(<PackForm isPending={false} onConfirm={onConfirm} onCancel={() => {}} />);
    fireEvent.change(screen.getByLabelText(/weight/i), { target: { value: '2.5' } });
    fireEvent.click(screen.getByRole('button', { name: /confirm pack/i }));
    expect(onConfirm).toHaveBeenCalledWith(2.5, 'CARTON');
  });
});
