import { fireEvent, render, screen } from '@testing-library/react'
import { RouterProvider } from 'react-router'
import { beforeEach, expect, test, vi } from 'vitest'

const auth = vi.hoisted(() => ({
  authenticated: false,
  login: vi.fn(),
  logout: vi.fn(),
}))

vi.mock('@/auth/auth-provider', () => ({ useAuth: () => auth }))

beforeEach(() => {
  vi.restoreAllMocks()
  vi.resetModules()
  auth.authenticated = false
  auth.login.mockReset()
  window.history.replaceState({}, '', '/m/pick/PICK%3A1?step=confirm#amount')
})

test('guarded floor login preserves the original workflow route', async () => {
  const { router } = await import('@/routes/router')

  render(<RouterProvider router={router} />)
  fireEvent.click(await screen.findByRole('button', { name: 'Log in' }))

  expect(auth.login).toHaveBeenCalledWith('/m/pick/PICK%3A1?step=confirm#amount')
})

test('signed-out floor login stays inside the PWA while offline', async () => {
  vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false)
  const { router } = await import('@/routes/router')

  render(<RouterProvider router={router} />)

  expect(await screen.findByRole('button', { name: 'Log in' })).toBeDisabled()
  expect(screen.getByRole('status')).toHaveTextContent('Offline. Reconnect to sign in.')
  expect(screen.getByRole('status')).toHaveTextContent('Completed work stays queued until then.')
  expect(window.location.pathname).toBe('/m/login')
  expect(auth.login).not.toHaveBeenCalled()
})
