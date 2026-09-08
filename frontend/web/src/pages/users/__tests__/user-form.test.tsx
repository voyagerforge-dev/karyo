import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';

const createMutate = vi.hoisted(() => vi.fn());

// Mock hooks
vi.mock('../use-users', () => ({
  useCreateUser: vi.fn(() => ({ mutate: createMutate, isPending: false })),
  useUpdateUser: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useAssignRole: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
  useRevokeRole: vi.fn(() => ({ mutate: vi.fn(), isPending: false })),
}));

vi.mock('@/features/clients/use-clients', () => ({
  useClients: vi.fn(() => ({
    data: [
      {
        id: 0,
        name: 'System',
        number: 'SYS',
        code: 'SYS',
        email: '',
        phone: '',
        fax: '',
        state: 'ACTIVE',
        isSystemClient: true,
      },
      {
        id: 1,
        name: 'ACME Corporation',
        number: 'ACME',
        code: 'ACME',
        email: '',
        phone: '',
        fax: '',
        state: 'ACTIVE',
        isSystemClient: false,
      },
    ],
    isLoading: false,
  })),
}));

import { UserForm } from '../user-form';
import type { UserResponse } from '@/types/user';

const mockOnClose = vi.fn();

beforeEach(() => {
  vi.clearAllMocks();
});

describe('UserForm', () => {
  it('renders password field in create mode', () => {
    render(<UserForm onClose={mockOnClose} />);

    expect(screen.getByLabelText('Password')).toBeInTheDocument();
  });

  it('hides password field in edit mode', () => {
    const user: UserResponse = {
      id: 'uuid-1',
      username: 'admin',
      email: 'admin@example.com',
      firstName: 'Admin',
      lastName: 'User',
      enabled: true,
      roles: ['ADMIN'],
      tenantCode: 'ACME',
      warehouseId: null,
      createdTimestamp: null,
    };

    render(<UserForm user={user} onClose={mockOnClose} />);

    expect(screen.queryByLabelText('Password')).not.toBeInTheDocument();
  });

  it('renders role checkboxes', () => {
    render(<UserForm onClose={mockOnClose} />);

    expect(screen.getByLabelText('ADMIN')).toBeInTheDocument();
    expect(screen.getByLabelText('OPERATOR')).toBeInTheDocument();
    expect(screen.getByLabelText('MANAGER')).toBeInTheDocument();
  });

  it('create mode collects identity, owner, authority, roles, and warehouse details', () => {
    render(<UserForm onClose={mockOnClose} />);

    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
    expect(screen.getByLabelText('First Name')).toBeInTheDocument();
    expect(screen.getByLabelText('Last Name')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByLabelText('Warehouse ID')).toBeInTheDocument();
    expect(screen.getByLabelText('ADMIN')).toBeInTheDocument();
    expect(screen.getByLabelText('Goods owner')).toBeInTheDocument();
    expect(screen.getByLabelText('Tenant authority')).toBeInTheDocument();
  });

  it('shows validation errors for empty required fields in create mode', async () => {
    const user = userEvent.setup();
    render(<UserForm onClose={mockOnClose} />);

    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(screen.getByText(/username is required/i)).toBeInTheDocument();
    expect(screen.getByText(/email is required/i)).toBeInTheDocument();
    expect(screen.getByText(/goods owner is required/i)).toBeInTheDocument();
    expect(screen.getByText(/tenant authority is required/i)).toBeInTheDocument();
    expect(createMutate).not.toHaveBeenCalled();
  });

  it('creates a user for the selected goods owner', async () => {
    const user = userEvent.setup();
    render(<UserForm onClose={mockOnClose} />);

    await user.type(screen.getByLabelText('Username'), 'warehouse-manager');
    await user.type(screen.getByLabelText('Email'), 'manager@acme.test');
    await user.type(screen.getByLabelText('First Name'), 'Warehouse');
    await user.type(screen.getByLabelText('Last Name'), 'Manager');
    await user.selectOptions(screen.getByLabelText('Goods owner'), '1');
    await user.selectOptions(screen.getByLabelText('Tenant authority'), 'owner');
    await user.type(screen.getByLabelText('Password'), 'secure-password-123');
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(createMutate).toHaveBeenCalledWith(
      expect.objectContaining({
        clientId: 1,
        username: 'warehouse-manager',
        principalKind: 'owner',
      }),
      expect.any(Object),
    );
  });

  it('sends the operations authority only when it is explicitly selected', async () => {
    const user = userEvent.setup();
    render(<UserForm onClose={mockOnClose} />);

    await user.type(screen.getByLabelText('Username'), 'ops-admin');
    await user.type(screen.getByLabelText('Email'), 'ops@acme.test');
    await user.type(screen.getByLabelText('First Name'), 'Ops');
    await user.type(screen.getByLabelText('Last Name'), 'Admin');
    await user.selectOptions(screen.getByLabelText('Goods owner'), '0');
    await user.selectOptions(screen.getByLabelText('Tenant authority'), 'ops');
    await user.type(screen.getByLabelText('Password'), 'secure-password-123');
    await user.click(screen.getByRole('button', { name: /save/i }));

    expect(createMutate).toHaveBeenCalledWith(
      expect.objectContaining({ clientId: 0, principalKind: 'ops' }),
      expect.any(Object),
    );
  });
});
