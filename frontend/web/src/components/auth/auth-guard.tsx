import { useEffect } from 'react';
import { Outlet } from 'react-router';
import { useAuth } from '@/components/auth/auth-provider';
import { ErrorPage } from '@/components/feedback/error-page';

/**
 * Route guard for protected routes. Keycloak is initialized with `check-sso`
 * (see lib/keycloak.ts). This guard is the runtime fallback if the session is
 * missing or later drops (token fully expired, refresh failed): it starts
 * login and returns to `/`. Silo tenancy means there is no tenant/workspace
 * picker - sign-in is the only front door.
 */
export function AuthGuard() {
  const { authenticated, loginError, login } = useAuth();

  useEffect(() => {
    if (!authenticated) {
      login();
    }
  }, [authenticated, login]);

  if (!authenticated) {
    return loginError ? (
      <div
        role="alert"
        className="flex min-h-screen flex-col items-center justify-center bg-background"
      >
        <ErrorPage
          title="Sign-in unavailable"
          description={loginError}
          onRetry={login}
        />
      </div>
    ) : null;
  }

  return <Outlet />;
}
