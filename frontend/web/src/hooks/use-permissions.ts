import { useCallback, useMemo } from 'react';
import { useAuth } from '@/components/auth/auth-provider';

/**
 * Custom hook for checking user permissions from JWT.
 * Reads permissions from the AuthProvider context.
 */
export function usePermissions() {
  const { permissions } = useAuth();

  const hasPermission = useCallback(
    (permission: string): boolean => permissions.includes(permission),
    [permissions],
  );

  const hasAnyPermission = useCallback(
    (requiredPermissions: string[]): boolean =>
      requiredPermissions.some((p) => permissions.includes(p)),
    [permissions],
  );

  return useMemo(
    () => ({
      permissions,
      hasPermission,
      hasAnyPermission,
    }),
    [permissions, hasPermission, hasAnyPermission],
  );
}
