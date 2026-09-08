import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
} from 'react';
import { endSession, keycloak, refreshSession, startLogin } from '@/lib/keycloak';
import type { AuthState } from '@/types/auth';

const AuthContext = createContext<AuthState | undefined>(undefined);

export const LOGIN_REDIRECT_FAILED =
  'Could not reach the sign-in service. Check the connection and try again.';

/**
 * Extract fine-grained permissions from Keycloak JWT.
 * Composite roles are already expanded at token issuance.
 */
function extractPermissions(
  tokenParsed: Keycloak.KeycloakTokenParsed | undefined,
): string[] {
  if (!tokenParsed?.realm_access?.roles) return [];
  return tokenParsed.realm_access.roles;
}

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [authenticated, setAuthenticated] = useState(keycloak.authenticated ?? false);
  const [token, setToken] = useState(keycloak.token);
  const [loginError, setLoginError] = useState<string | undefined>(undefined);
  const refreshRef = useRef<ReturnType<typeof setInterval> | null>(null);

  const permissions = extractPermissions(keycloak.tokenParsed);
  const userName = keycloak.tokenParsed?.preferred_username as string | undefined;
  const tenantCode = keycloak.tokenParsed?.tenant_code as string | undefined;

  const login = useCallback(() => {
    setLoginError(undefined);
    void startLogin().catch((error) => {
      console.error('[Keycloak] Login redirect failed:', error);
      setLoginError(LOGIN_REDIRECT_FAILED);
    });
  }, []);

  const logout = useCallback(() => {
    endSession();
  }, []);

  useEffect(() => {
    // Update state when auth events fire
    const onAuthSuccess = () => {
      setAuthenticated(true);
      setToken(keycloak.token);
    };
    const onAuthLogout = () => {
      setAuthenticated(false);
      setToken(undefined);
    };
    const onTokenRefreshed = () => {
      setToken(keycloak.token);
    };

    keycloak.onAuthSuccess = onAuthSuccess;
    keycloak.onAuthLogout = onAuthLogout;
    keycloak.onTokenExpired = () => {
      void refreshSession(60).catch(() => undefined);
    };
    keycloak.onAuthRefreshSuccess = onTokenRefreshed;

    refreshRef.current = setInterval(() => {
      void refreshSession(60).catch(() => undefined);
    }, 30_000);

    return () => {
      if (refreshRef.current) clearInterval(refreshRef.current);
    };
  }, []);

  const state: AuthState = {
    initialized: true,
    authenticated,
    token,
    permissions,
    userName,
    tenantCode,
    loginError,
    login,
    logout,
  };

  return <AuthContext.Provider value={state}>{children}</AuthContext.Provider>;
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth(): AuthState {
  const context = useContext(AuthContext);
  if (context === undefined)
    throw new Error('useAuth must be used within an AuthProvider');
  return context;
}
