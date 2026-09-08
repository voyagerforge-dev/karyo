import { createContext, useCallback, useContext, useEffect, useState } from 'react'
import { endSession, keycloak, refreshSession, startLogin } from '@/lib/keycloak'

interface AuthState { authenticated: boolean; userName?: string; loginError?: string; login: (intendedRoute?: string) => void; logout: () => void }
const AuthContext = createContext<AuthState | null>(null)

export const LOGIN_REDIRECT_FAILED = 'Could not reach sign-in. Check the connection and try again.'

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [authenticated, setAuthenticated] = useState(keycloak.authenticated ?? false)
  const [loginError, setLoginError] = useState<string | undefined>(undefined)
  useEffect(() => {
    keycloak.onAuthSuccess = () => setAuthenticated(true)
    keycloak.onAuthLogout = () => setAuthenticated(false)
    const id = setInterval(() => { void refreshSession(60).catch(() => undefined) }, 30_000)
    return () => clearInterval(id)
  }, [])
  const login = useCallback((intendedRoute?: string) => {
    setLoginError(undefined)
    void startLogin(intendedRoute).catch((error) => {
      console.error('[Keycloak] Login redirect failed:', error)
      setLoginError(LOGIN_REDIRECT_FAILED)
    })
  }, [])
  const logout = useCallback(() => { endSession() }, [])
  const userName = keycloak.tokenParsed?.preferred_username as string | undefined
  return <AuthContext.Provider value={{ authenticated, userName, loginError, login, logout }}>{children}</AuthContext.Provider>
}

// eslint-disable-next-line react-refresh/only-export-components
export function useAuth() {
  const ctx = useContext(AuthContext)
  if (!ctx) throw new Error('useAuth outside AuthProvider')
  return ctx
}
