import { act, fireEvent, render, screen } from '@testing-library/react'
import { MemoryRouter } from 'react-router'
import { afterEach, beforeEach, expect, test, vi } from 'vitest'

/**
 * Only keycloak-js is mocked. Mocking '@/lib/keycloak' instead would stub out refreshSession --
 * the one place the expiry-vs-network discrimination lives -- and the provider never calls
 * startLogin itself, so the assertion below would hold for every possible implementation,
 * including one that signs an operator out on an ordinary network blip.
 */
const keycloakMock = vi.hoisted(() => ({
  authenticated: true,
  tokenParsed: { preferred_username: 'operator' } as Record<string, unknown> | undefined,
  createLoginUrl: vi.fn<() => Promise<string>>(),
  updateToken: vi.fn<(minValidity: number) => Promise<boolean>>(),
  logout: vi.fn(),
  instance: undefined as { authenticated: boolean } | undefined,
}))

vi.mock('keycloak-js', () => ({
  default: class MockKeycloak {
    authenticated = keycloakMock.authenticated
    tokenParsed = keycloakMock.tokenParsed
    createLoginUrl = keycloakMock.createLoginUrl
    updateToken = keycloakMock.updateToken
    logout = keycloakMock.logout
    constructor() {
      keycloakMock.instance = this
    }
  },
}))

beforeEach(() => {
  vi.useFakeTimers()
  vi.resetModules()
  keycloakMock.authenticated = true
  keycloakMock.createLoginUrl.mockReset()
  // Rejecting stops startLogin before window.location.assign, which jsdom will not let a test
  // replace. Whether the adapter reached for a login URL is the observable that matters here.
  keycloakMock.createLoginUrl.mockRejectedValue(new Error('login redirect observed'))
  keycloakMock.updateToken.mockReset()
  vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true)
})

afterEach(() => {
  vi.useRealTimers()
  vi.restoreAllMocks()
})

async function renderProvider() {
  const { AuthProvider } = await import('@/auth/auth-provider')
  render(<AuthProvider><p>Floor workflow</p></AuthProvider>)
  await act(async () => {
    vi.advanceTimersByTime(30_000)
    await Promise.resolve()
    await Promise.resolve()
  })
}

test('transient periodic refresh failure keeps the floor PWA open', async () => {
  keycloakMock.updateToken.mockRejectedValue(new Error('network unavailable'))

  await renderProvider()

  expect(keycloakMock.updateToken).toHaveBeenCalledWith(60)
  expect(keycloakMock.createLoginUrl).not.toHaveBeenCalled()
})

test('a genuinely expired session reauthenticates from the periodic refresh', async () => {
  keycloakMock.updateToken.mockImplementation(async () => {
    // What keycloak-js clearToken() does when the refresh endpoint answers 400.
    keycloakMock.instance!.authenticated = false
    throw new Error('refresh token expired')
  })

  await renderProvider()

  expect(keycloakMock.createLoginUrl).toHaveBeenCalledWith({
    redirectUri: `${window.location.origin}/m/`,
  })
})

test('a failed login redirect surfaces a visible error on the floor login screen', async () => {
  keycloakMock.authenticated = false
  const { AuthProvider, LOGIN_REDIRECT_FAILED } = await import('@/auth/auth-provider')
  const { Login } = await import('@/screens/login')

  render(
    <MemoryRouter initialEntries={['/m/login']}>
      <AuthProvider><Login /></AuthProvider>
    </MemoryRouter>,
  )

  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Log in' }))
    await Promise.resolve()
    await Promise.resolve()
  })

  expect(keycloakMock.createLoginUrl).toHaveBeenCalled()
  expect(screen.getByRole('alert')).toHaveTextContent(LOGIN_REDIRECT_FAILED)
})
