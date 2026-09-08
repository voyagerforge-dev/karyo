import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { ManifestForm } from '../manifest-form';

describe('ManifestForm', () => {
  it('submits carrier + service + tracking', () => {
    const onConfirm = vi.fn();
    render(<ManifestForm isPending={false} onConfirm={onConfirm} onCancel={() => {}} />);
    fireEvent.change(screen.getByLabelText(/service/i), { target: { value: 'GROUND' } });
    fireEvent.change(screen.getByLabelText(/tracking/i), { target: { value: '1Z999' } });
    fireEvent.click(screen.getByRole('button', { name: /confirm manifest/i }));
    expect(onConfirm).toHaveBeenCalledWith('MANUAL', 'GROUND', '1Z999');
  });

  it('blocks an empty service', () => {
    const onConfirm = vi.fn();
    render(<ManifestForm isPending={false} onConfirm={onConfirm} onCancel={() => {}} />);
    fireEvent.click(screen.getByRole('button', { name: /confirm manifest/i }));
    expect(onConfirm).not.toHaveBeenCalled();
    expect(screen.getByText(/service is required/i)).toBeInTheDocument();
  });
});
