import { Navigate, Outlet } from 'react-router'
import { useAuth } from '@/auth/auth-provider'

export function AuthGuard() {
  const { authenticated } = useAuth()
  const intendedRoute = `${window.location.pathname}${window.location.search}${window.location.hash}`
  return authenticated
    ? <Outlet />
    : <Navigate to="/login" replace state={{ intendedRoute }} />
}
