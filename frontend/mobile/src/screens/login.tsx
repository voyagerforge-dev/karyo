import { Navigate, useLocation } from 'react-router'
import { useAuth } from '@/auth/auth-provider'
import { useOnline } from '@/lib/offline/use-online'

export function Login() {
  const { authenticated, login, loginError } = useAuth()
  const online = useOnline()
  const location = useLocation()
  const intendedRoute = typeof location.state?.intendedRoute === 'string'
    ? location.state.intendedRoute
    : undefined
  if (authenticated) return <Navigate to="/" replace />
  return (
    <div style={{ display: 'flex', flexDirection: 'column', justifyContent: 'center', height: '100%', padding: 24, gap: 24 }}>
      <h1 className="numeric" style={{ fontSize: 40, letterSpacing: '-0.02em' }}>KARYO</h1>
      <button disabled={!online} onClick={() => login(intendedRoute)} style={{ minHeight: 64, fontSize: 22, background: 'var(--floor-accent)', color: '#fff', border: 0, borderRadius: 12 }}>
        Log in
      </button>
      {!online && (
        <div role="status">
          <p>Offline. Reconnect to sign in.</p>
          <p>Completed work stays queued until then.</p>
        </div>
      )}
      {online && loginError && <p role="alert">{loginError}</p>}
    </div>
  )
}
