import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { AdminGuard } from '@/components/auth/admin-guard';

const mockUsePermissions = vi.fn();

vi.mock('@/hooks/use-permissions', () => ({
  usePermissions: () => mockUsePermissions(),
}));

function renderGuard() {
  return render(
    <MemoryRouter initialEntries={['/admin/audit']}>
      <Routes>
        <Route element={<AdminGuard />}>
          <Route path="/admin/audit" element={<div data-testid="admin-audit-page">AUDIT</div>} />
        </Route>
        <Route path="/" element={<div data-testid="home-page">HOME</div>} />
      </Routes>
    </MemoryRouter>,
  );
}

describe('AdminGuard', () => {
  beforeEach(() => {
    mockUsePermissions.mockReset();
  });

  it('renders the admin subtree for a user with the user-admin permission (ADMIN role)', () => {
    mockUsePermissions.mockReturnValue({
      hasPermission: (p: string) => p === 'user-admin',
    });

    renderGuard();

    expect(screen.getByTestId('admin-audit-page')).toBeInTheDocument();
    expect(screen.queryByTestId('home-page')).not.toBeInTheDocument();
  });

  it('redirects a non-admin (e.g. VIEWER) away from the admin subtree', () => {
    mockUsePermissions.mockReturnValue({
      hasPermission: (p: string) =>
        ['inventory-read', 'product-read', 'layout-read', 'order-read', 'task-read', 'report-read'].includes(p),
    });

    renderGuard();

    expect(screen.getByTestId('home-page')).toBeInTheDocument();
    expect(screen.queryByTestId('admin-audit-page')).not.toBeInTheDocument();
  });

  it('redirects a user with no permissions at all', () => {
    mockUsePermissions.mockReturnValue({ hasPermission: () => false });

    renderGuard();

    expect(screen.getByTestId('home-page')).toBeInTheDocument();
    expect(screen.queryByTestId('admin-audit-page')).not.toBeInTheDocument();
  });

  function renderGuardWithPermission(permission: string) {
    return render(
      <MemoryRouter initialEntries={['/admin/integrations']}>
        <Routes>
          <Route element={<AdminGuard permission={permission} />}>
            <Route
              path="/admin/integrations"
              element={<div data-testid="admin-integrations-page">INTEGRATIONS</div>}
            />
          </Route>
          <Route path="/" element={<div data-testid="home-page">HOME</div>} />
        </Routes>
      </MemoryRouter>,
    );
  }

  it('renders the subtree when the caller holds the custom permission (e.g. integration-admin)', () => {
    mockUsePermissions.mockReturnValue({
      hasPermission: (p: string) => p === 'integration-admin',
    });

    renderGuardWithPermission('integration-admin');

    expect(screen.getByTestId('admin-integrations-page')).toBeInTheDocument();
    expect(screen.queryByTestId('home-page')).not.toBeInTheDocument();
  });

  it('redirects away from a custom-permission subtree even when the caller holds user-admin but not the custom permission', () => {
    mockUsePermissions.mockReturnValue({
      hasPermission: (p: string) => p === 'user-admin',
    });

    renderGuardWithPermission('integration-admin');

    expect(screen.getByTestId('home-page')).toBeInTheDocument();
    expect(screen.queryByTestId('admin-integrations-page')).not.toBeInTheDocument();
  });
});
