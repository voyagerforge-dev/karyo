import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { MemoryRouter, Navigate, Route, Routes, useLocation } from 'react-router';

/**
 * D3: /pick-orders and /replenishment are superseded by the unified /tasks
 * view (Task type filter). These are pure client-side redirects -- no page
 * component survives at the old paths.
 *
 * This harness mirrors the redirect route elements router.tsx defines,
 * without pulling in the full router (and its full page-tree of imports).
 */
function Probe() {
  const location = useLocation();
  return (
    <div data-testid="probe">
      {location.pathname}
      {location.search}
    </div>
  );
}

function RedirectHarness({ initialPath }: { initialPath: string }) {
  return (
    <MemoryRouter initialEntries={[initialPath]}>
      <Routes>
        <Route
          path="/pick-orders"
          element={<Navigate to={{ pathname: '/tasks', search: '?type=PICK' }} replace />}
        />
        <Route
          path="/replenishment"
          element={<Navigate to={{ pathname: '/tasks', search: '?type=REPLENISH' }} replace />}
        />
        <Route path="/tasks" element={<Probe />} />
      </Routes>
    </MemoryRouter>
  );
}

describe('superseded-page redirects (D3)', () => {
  it('/pick-orders redirects to /tasks?type=PICK', () => {
    render(<RedirectHarness initialPath="/pick-orders" />);
    const probe = screen.getByTestId('probe');
    expect(probe.textContent).toBe('/tasks?type=PICK');
  });

  it('/replenishment redirects to /tasks?type=REPLENISH', () => {
    render(<RedirectHarness initialPath="/replenishment" />);
    const probe = screen.getByTestId('probe');
    expect(probe.textContent).toBe('/tasks?type=REPLENISH');
  });
});
