import { Navigate, Outlet } from 'react-router';
import { usePermissions } from '@/hooks/use-permissions';

/**
 * Route guard that restricts a subtree to a required realm permission.
 * Mirrors AuthGuard (auth-guard.tsx) but checks role instead of
 * authentication -- use nested *inside* AuthGuard so unauthenticated users
 * still get redirected to sign-in first.
 *
 * `user-admin` is the fine-grained permission unique to the ADMIN realm role
 * (see infrastructure/keycloak/karyo-realm.json) -- the same signal
 * users-page.tsx already uses as its `isAdmin` check, so it stays the
 * default. Individual admin sub-routes that are gated on a narrower,
 * dedicated permission (e.g. `/admin/integrations` on `integration-admin`,
 * held by `manager` without full `user-admin`) pass that permission
 * explicitly via the `permission` prop instead.
 */
export function AdminGuard({ permission = 'user-admin' }: { permission?: string }) {
  const { hasPermission } = usePermissions();

  if (!hasPermission(permission)) {
    return <Navigate to="/" replace />;
  }

  return <Outlet />;
}
