import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { fireEvent, render, screen, waitFor, within } from '@testing-library/react';
import { MemoryRouter, useNavigate } from 'react-router';
import userEvent from '@testing-library/user-event';
import type { PaginatedResponse } from '@/types/api';
import type { UserResponse } from '@/types/user';

const createMutate = vi.fn();
const updateMutate = vi.fn();
const deactivateMutate = vi.fn();
const reactivateMutate = vi.fn();
const assignRoleMutate = vi.fn();
const revokeRoleMutate = vi.fn();

vi.mock('../use-users', () => ({
  useUsers: vi.fn(),
  useUser: vi.fn(),
  useCreateUser: vi.fn(() => ({ mutate: createMutate, isPending: false })),
  useUpdateUser: vi.fn(() => ({ mutate: updateMutate, isPending: false })),
  useDeactivateUser: vi.fn(() => ({ mutate: deactivateMutate, isPending: false })),
  useReactivateUser: vi.fn(() => ({ mutate: reactivateMutate, isPending: false })),
  useAssignRole: vi.fn(() => ({ mutate: assignRoleMutate, isPending: false })),
  useRevokeRole: vi.fn(() => ({ mutate: revokeRoleMutate, isPending: false })),
}));

// The create form pulls the goods-owner list through react-query. Without this, rendering it
// inside a bare MemoryRouter throws "No QueryClient set" and every ?create=1 scenario fails.
vi.mock('@/features/clients/use-clients', () => ({
  useClients: vi.fn(() => ({
    data: [
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

let mockRoles = ['user-admin'];
vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: vi.fn(() => ({
    permissions: mockRoles,
    hasPermission: (p: string) => mockRoles.includes(p),
    hasAnyPermission: () => true,
  })),
}));

const admin: UserResponse = {
  id: 'uuid-1',
  username: 'admin',
  email: 'admin@example.com',
  firstName: 'Admin',
  lastName: 'User',
  enabled: true,
  roles: ['ADMIN', 'MANAGER'],
  tenantCode: 'ACME',
  warehouseId: null,
  createdTimestamp: 1709251200000,
};

const operator1: UserResponse = {
  id: 'uuid-2',
  username: 'operator1',
  email: 'op1@example.com',
  firstName: 'Jane',
  lastName: 'Doe',
  enabled: false,
  roles: ['OPERATOR'],
  tenantCode: 'ACME',
  warehouseId: null,
  createdTimestamp: 1709337600000,
};

const mockUsers: UserResponse[] = [admin, operator1];

const mockPaginated: PaginatedResponse<UserResponse> = {
  content: mockUsers,
  page: { number: 0, size: 100, totalElements: 2, totalPages: 1 },
};

// Import after mocks
import { useUsers, useUser } from '../use-users';
import { UsersPage } from '../users-page';

function renderPage() {
  return render(
    <MemoryRouter>
      <UsersPage />
    </MemoryRouter>,
  );
}

beforeEach(() => {
  createMutate.mockClear();
  updateMutate.mockClear();
  deactivateMutate.mockClear();
  reactivateMutate.mockClear();
  assignRoleMutate.mockClear();
  revokeRoleMutate.mockClear();

  vi.mocked(useUsers).mockReturnValue({
    data: mockPaginated,
    isLoading: false,
    isError: false,
    error: null,
  } as ReturnType<typeof useUsers>);

  vi.mocked(useUser).mockImplementation(
    (id?: string) =>
      ({
        data: mockUsers.find((u) => u.id === id),
        isLoading: false,
      }) as ReturnType<typeof useUser>,
  );
});

afterEach(() => {
  mockRoles = ['user-admin'];
  window.localStorage.clear();
});

function userRowNodes(): NodeListOf<Element> {
  return document.querySelectorAll('[data-testid^="user-row-"]');
}

describe('UsersPage', () => {
  it('renders user rows with names', () => {
    renderPage();

    expect(within(screen.getByTestId('user-row-uuid-1')).getByText('Admin User')).toBeInTheDocument();
    expect(within(screen.getByTestId('user-row-uuid-2')).getByText('Jane Doe')).toBeInTheDocument();
  });

  it('shows role pills in the pane after a row click', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('user-row-uuid-1'));

    const pane = screen.getByTestId('user-detail');
    expect(within(pane).getByText('ADMIN')).toBeInTheDocument();
    expect(within(pane).getByText('MANAGER')).toBeInTheDocument();
  });

  it('renders Active/Deactivated status for rows and in the pane', async () => {
    const user = userEvent.setup();
    renderPage();

    expect(within(screen.getByTestId('user-row-uuid-1')).getByText('Active')).toBeInTheDocument();
    expect(within(screen.getByTestId('user-row-uuid-2')).getByText('Deactivated')).toBeInTheDocument();

    await user.click(screen.getByTestId('user-row-uuid-2'));
    expect(within(screen.getByTestId('user-detail')).getByText('Deactivated')).toBeInTheDocument();
  });

  it('renders the Create User button', () => {
    renderPage();

    expect(screen.getByTestId('user-create-button')).toBeInTheDocument();
  });

  // I-1 (final review): the ⌘K palette's "Create user" command deep-links to
  // /users?create=1 (command-palette.tsx) — the recomposed page must honor it
  // the same way ReceivingPage/OrdersPage do.
  it('opens the create form when navigated with ?create=1 (⌘K deep link)', () => {
    render(
      <MemoryRouter initialEntries={['/users?create=1']}>
        <UsersPage />
      </MemoryRouter>,
    );

    expect(screen.getByText('New user')).toBeInTheDocument();
  });

  // ⌘K "Create user" while ALREADY on /users: the useState initializer never
  // re-runs, so the searchParams effect must apply the param (TasksPage idiom).
  it('applies a ?create=1 param pushed while the page stays mounted', async () => {
    const user = userEvent.setup();
    function Harness() {
      const navigate = useNavigate();
      return (
        <>
          <button data-testid="nav-create" onClick={() => navigate('/users?create=1')}>
            nav
          </button>
          <UsersPage />
        </>
      );
    }
    render(
      <MemoryRouter initialEntries={['/users']}>
        <Harness />
      </MemoryRouter>,
    );

    expect(screen.queryByText('New user')).not.toBeInTheDocument();

    await user.click(screen.getByTestId('nav-create'));

    expect(await screen.findByText('New user')).toBeInTheDocument();
  });

  it('deactivate action is reachable in the pane', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('user-row-uuid-1'));
    expect(screen.getByTestId('user-deactivate-button')).toBeInTheDocument();

    await user.click(screen.getByTestId('user-row-uuid-2'));
    expect(screen.getByTestId('user-reactivate-button')).toBeInTheDocument();
  });

  it('shows the unauthorized block when user-admin is missing', () => {
    mockRoles = [];
    renderPage();

    expect(screen.getByText('Unauthorized')).toBeInTheDocument();
    expect(screen.queryByTestId('users-page')).not.toBeInTheDocument();
  });

  // Pins the `key={selectedId}` remount on UserDetail (I-2 pattern from
  // cycle-count/receiving): without it, switching from A to B would carry A's
  // open edit-form state into B's pane.
  it('switching the selected row resets an open edit state (selection-switch)', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('user-row-uuid-1'));
    await user.click(screen.getByTestId('user-edit-toggle'));
    expect(screen.getByText('Edit user')).toBeInTheDocument();

    await user.click(screen.getByTestId('user-row-uuid-2'));

    // B's data renders in the pane...
    expect(within(screen.getByTestId('user-detail')).getByText('Jane Doe')).toBeInTheDocument();
    // ...and the edit form from A is gone (remounted, not carried over).
    expect(screen.queryByText('Edit user')).not.toBeInTheDocument();
  });

  it('create form collects identity, owner, authority, roles, and warehouse details', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('user-create-button'));

    expect(screen.getByText('New user')).toBeInTheDocument();
    expect(screen.getByLabelText('Username')).toBeInTheDocument();
    expect(screen.getByLabelText('Email')).toBeInTheDocument();
    expect(screen.getByLabelText('First Name')).toBeInTheDocument();
    expect(screen.getByLabelText('Last Name')).toBeInTheDocument();
    expect(screen.getByLabelText('Password')).toBeInTheDocument();
    expect(screen.getByLabelText('Warehouse ID')).toBeInTheDocument();
    expect(screen.getByLabelText('ADMIN')).toBeInTheDocument();
    expect(screen.getByLabelText('OPERATOR')).toBeInTheDocument();
    expect(screen.getByLabelText('Goods owner')).toBeInTheDocument();
    expect(screen.getByLabelText('Tenant authority')).toBeInTheDocument();
  });

  it('pages to users beyond the first 100 results', async () => {
    const user = userEvent.setup();
    const lastUser: UserResponse = {
      ...operator1,
      id: 'uuid-101',
      username: 'operator101',
      firstName: 'Last',
      lastName: 'Operator',
    };
    vi.mocked(useUsers).mockImplementation((options) => ({
      data: options.page === 0
        ? {
            ...mockPaginated,
            page: { number: 0, size: 100, totalElements: 101, totalPages: 2 },
          }
        : {
            content: [lastUser],
            page: { number: 1, size: 100, totalElements: 101, totalPages: 2 },
          },
      isLoading: false,
      isError: false,
      error: null,
    }) as ReturnType<typeof useUsers>);

    renderPage();

    expect(screen.queryByText('Last Operator')).not.toBeInTheDocument();
    await user.click(screen.getByRole('button', { name: 'Next' }));

    expect(await screen.findByText('Last Operator')).toBeInTheDocument();
    expect(useUsers).toHaveBeenLastCalledWith(expect.objectContaining({ page: 1 }));
  });

  it('clamps the current page when refreshed totals shrink', async () => {
    const user = userEvent.setup();
    let totalPages = 2;
    vi.mocked(useUsers).mockImplementation((options) => ({
      data: {
        content: options.page < totalPages ? mockUsers : [],
        page: {
          number: options.page,
          size: 100,
          totalElements: totalPages * 100,
          totalPages,
        },
      },
      isLoading: false,
      isError: false,
      error: null,
    }) as ReturnType<typeof useUsers>);
    const view = renderPage();

    await user.click(screen.getByRole('button', { name: 'Next' }));
    expect(screen.getByText('2 / 2')).toBeInTheDocument();

    totalPages = 1;
    view.rerender(
      <MemoryRouter>
        <UsersPage />
      </MemoryRouter>,
    );

    await waitFor(() => {
      expect(useUsers).toHaveBeenLastCalledWith(expect.objectContaining({ page: 0 }));
    });
    expect(screen.getByText('1 / 1')).toBeInTheDocument();
    expect(screen.getByText('Admin User')).toBeInTheDocument();
  });

  it('searching a unique username isolates a named admin from a capped leftover list', async () => {
    window.localStorage.setItem('karyo:list-row-limit', '50');
    const leftovers: UserResponse[] = Array.from({ length: 51 }, (_, i) => ({
      id: `uuid-e2e-${i}`,
      username: `e2e-user-${i}`,
      email: `e2e-user-${i}@e2e-test.local`,
      firstName: 'E2E',
      lastName: `Leftover${i}`,
      enabled: true,
      roles: ['OPERATOR'],
      tenantCode: 'ACME',
      warehouseId: null,
      createdTimestamp: 1_700_000_000_000 + i,
    }));
    const jane: UserResponse = {
      id: 'uuid-jane',
      username: 'jdoe',
      email: 'jane@acme.com',
      firstName: 'Jane',
      lastName: 'Doe',
      enabled: true,
      roles: ['ADMIN'],
      tenantCode: 'ACME',
      warehouseId: null,
      createdTimestamp: 1_709_251_200_000,
    };
    vi.mocked(useUsers).mockImplementation((options) => ({
      data: options.search === 'jdoe'
        ? {
            content: [jane],
            page: { number: 0, size: 100, totalElements: 1, totalPages: 1 },
          }
        : {
            content: leftovers,
            page: { number: 0, size: 100, totalElements: 101, totalPages: 2 },
          },
      isLoading: false,
      isError: false,
      error: null,
    }) as ReturnType<typeof useUsers>);

    renderPage();

    expect(userRowNodes()).toHaveLength(50);
    expect(screen.queryByText('Jane Doe')).not.toBeInTheDocument();
    expect(screen.getByText('E2E Leftover0')).toBeInTheDocument();

    fireEvent.change(
      screen.getByPlaceholderText('Search username, name or email…'),
      { target: { value: 'jdoe' } },
    );

    await waitFor(() => {
      expect(userRowNodes()).toHaveLength(1);
    });
    const isolated = userRowNodes()[0];
    expect(isolated).toHaveTextContent('Jane Doe');
    expect(isolated).toHaveTextContent('jane@acme.com');
    expect(isolated).toHaveTextContent('ADMIN');
    expect(screen.queryByText('E2E Leftover0')).not.toBeInTheDocument();
    expect(useUsers).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 0, search: 'jdoe' }),
    );
  });

  it('confirm dialog drives the deactivate mutation; cancel does not call it', async () => {
    const user = userEvent.setup();
    renderPage();

    await user.click(screen.getByTestId('user-row-uuid-1'));
    await user.click(screen.getByTestId('user-deactivate-button'));

    const dialog = screen.getByRole('alertdialog');
    expect(within(dialog).getByText('Deactivate User?')).toBeInTheDocument();

    await user.click(within(dialog).getByText('Cancel'));
    expect(deactivateMutate).not.toHaveBeenCalled();

    await user.click(screen.getByTestId('user-deactivate-button'));
    await user.click(screen.getByRole('button', { name: 'Deactivate' }));

    expect(deactivateMutate).toHaveBeenCalledWith('uuid-1', expect.anything());
  });
});
