import { beforeEach, describe, expect, it, vi } from 'vitest'

const keycloakMock = vi.hoisted(() => ({
  authenticated: false,
  createLoginUrl: vi.fn<() => Promise<string>>(),
  updateToken: vi.fn<(minValidity: number) => Promise<boolean>>(),
  logout: vi.fn(),
  instance: undefined as { authenticated: boolean } | undefined,
}))

vi.mock('keycloak-js', () => ({
  default: class MockKeycloak {
    authenticated = keycloakMock.authenticated
    createLoginUrl = keycloakMock.createLoginUrl
    updateToken = keycloakMock.updateToken
    logout = keycloakMock.logout
    constructor() {
      keycloakMock.instance = this
    }
  },
}))

const pendingRouteKey = 'karyo.floor.auth.pending-route'
const now = 2_000_000

async function loadKeycloak() {
  return import('@/lib/keycloak')
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.resetModules()
  keycloakMock.authenticated = false
  keycloakMock.createLoginUrl.mockReset()
  keycloakMock.updateToken.mockReset()
  keycloakMock.logout.mockReset()
  window.sessionStorage.clear()
  window.history.replaceState({}, '', '/m/')
})

describe('floor Keycloak redirects', () => {
  it('restores a workflow route bound to the successful OAuth state', async () => {
    window.history.replaceState({}, '', '/m/login')
    keycloakMock.createLoginUrl.mockResolvedValue(
      'https://identity.example.test/authorize?client_id=karyo-web&state=floor-state-1',
    )

    const login = await loadKeycloak()
    vi.spyOn(Date, 'now').mockReturnValue(now)
    await expect(login.createLoginRedirect('/m/pick/PICK%3A1?step=confirm#amount'))
      .resolves.toContain('state=floor-state-1')
    expect(login.initOptions.responseMode).toBe('query')
    expect(keycloakMock.createLoginUrl).toHaveBeenCalledWith({
      redirectUri: `${window.location.origin}/m/`,
    })
    expect(JSON.parse(window.sessionStorage.getItem(pendingRouteKey)!)).toEqual({
      oauthState: 'floor-state-1',
      route: '/m/pick/PICK%3A1?step=confirm#amount',
      createdAt: now,
    })

    window.history.replaceState({}, '', '/m/?state=floor-state-1&code=authorization-code')
    keycloakMock.authenticated = true
    vi.resetModules()
    const callback = await loadKeycloak()
    window.history.replaceState({}, '', '/m/')

    callback.restoreAuthenticationRoute('floor-state-1', now + 1_000)

    expect(`${window.location.pathname}${window.location.search}${window.location.hash}`)
      .toBe('/m/pick/PICK%3A1?step=confirm#amount')
    expect(window.sessionStorage.getItem(pendingRouteKey)).toBeNull()
  })

  it('preserves an authenticated floor route with an application state filter', async () => {
    window.history.replaceState({}, '', '/m/tasks?state=READY')
    window.sessionStorage.setItem(pendingRouteKey, JSON.stringify({
      oauthState: 'READY', route: '/m/pick/PICK%3A1', createdAt: now,
    }))
    keycloakMock.authenticated = true
    const callback = await loadKeycloak()

    const callbackState = callback.readAuthenticationCallbackState()
    callback.restoreAuthenticationRoute(callbackState, now)

    expect(callbackState).toBeNull()
    expect(`${window.location.pathname}${window.location.search}`).toBe('/m/tasks?state=READY')
    expect(window.sessionStorage.getItem(pendingRouteKey)).not.toBeNull()
  })

  it('reads state from OAuth success and error callbacks only', async () => {
    const callback = await loadKeycloak()

    expect(callback.readAuthenticationCallbackState('/m/?state=success&code=authorization-code'))
      .toBe('success')
    expect(callback.readAuthenticationCallbackState('/m/#state=denied&error=access_denied'))
      .toBe('denied')
    expect(callback.readAuthenticationCallbackState('/m/?state=filter&error='))
      .toBeNull()
  })

  it('coalesces concurrent logins onto one OAuth transaction', async () => {
    // The operator tapping "Log in" while an expiring session's refreshSession catch also starts
    // one would otherwise run two transactions over the single pending-route key, so the browser
    // could leave with one OAuth state while storage held the other.
    window.history.replaceState({}, '', '/m/login')
    let issued = 0
    keycloakMock.createLoginUrl.mockImplementation(async () => {
      issued += 1
      return `https://identity.example.test/authorize?client_id=karyo-web&state=floor-state-${issued}`
    })

    const login = await loadKeycloak()
    vi.spyOn(Date, 'now').mockReturnValue(now)
    await Promise.all([
      login.startLogin('/m/pick/PICK%3A1?step=confirm'),
      login.startLogin(),
    ])

    expect(keycloakMock.createLoginUrl).toHaveBeenCalledTimes(1)
    expect(JSON.parse(window.sessionStorage.getItem(pendingRouteKey)!)).toEqual({
      oauthState: 'floor-state-1',
      route: '/m/pick/PICK%3A1?step=confirm',
      createdAt: now,
    })

    // The only login URL ever generated carried floor-state-1, so that is what the browser was
    // sent with -- and the pick survives instead of the operator landing on '/m/'.
    window.history.replaceState({}, '', '/m/?state=floor-state-1&code=authorization-code')
    keycloakMock.authenticated = true
    vi.resetModules()
    const callback = await loadKeycloak()
    window.history.replaceState({}, '', '/m/')

    callback.restoreAuthenticationRoute('floor-state-1', now + 1_000)

    expect(`${window.location.pathname}${window.location.search}${window.location.hash}`)
      .toBe('/m/pick/PICK%3A1?step=confirm')
  })

  it('does not start a login transaction while offline', async () => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(false)
    window.history.replaceState({}, '', '/m/login')
    const login = await loadKeycloak()

    await login.startLogin('/m/pick/PICK%3A1')

    expect(keycloakMock.createLoginUrl).not.toHaveBeenCalled()
    expect(window.location.pathname).toBe('/m/login')
    expect(window.sessionStorage.getItem(pendingRouteKey)).toBeNull()
  })

  it.each([
    ['offline token refresh', false, false],
    ['transient online refresh failure', true, true],
  ])('does not leave the PWA after %s', async (_case, online, authenticated) => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(online)
    keycloakMock.authenticated = authenticated
    keycloakMock.updateToken.mockRejectedValue(new Error('identity service unavailable'))

    const session = await loadKeycloak()

    await expect(session.refreshSession(60)).rejects.toThrow('identity service unavailable')
    expect(keycloakMock.createLoginUrl).not.toHaveBeenCalled()
  })

  it('starts reauthentication when an established online session expires', async () => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true)
    keycloakMock.authenticated = true
    keycloakMock.createLoginUrl.mockRejectedValue(new Error('login redirect observed'))

    const session = await loadKeycloak()
    keycloakMock.updateToken.mockImplementation(async () => {
      // What keycloak-js clearToken() does when the refresh endpoint answers 400.
      keycloakMock.instance!.authenticated = false
      throw new Error('refresh token expired')
    })

    await expect(session.refreshSession(60)).rejects.toThrow('login redirect observed')
    expect(keycloakMock.createLoginUrl).toHaveBeenCalledWith({
      redirectUri: `${window.location.origin}/m/`,
    })
  })

  it('leaves a never-authenticated operator on the login screen', async () => {
    // check-sso found no session, so keycloak-js rejects before any network call and there is no
    // session to re-establish. Redirecting here would discard the route AuthGuard captured.
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true)
    keycloakMock.authenticated = false
    keycloakMock.updateToken.mockRejectedValue(
      new Error('Unable to update token, no refresh token available.'),
    )

    const session = await loadKeycloak()

    await expect(session.refreshSession(60)).rejects.toThrow('no refresh token available')
    expect(keycloakMock.createLoginUrl).not.toHaveBeenCalled()
  })

  it.each([
    ['a mismatched OAuth state', 'different-state', '/m/pick/PICK%3A1', now],
    ['a route outside the floor app', 'floor-state-2', '/orders/123', now],
    ['a stale route', 'floor-state-2', '/m/pick/PICK%3A1', now - 300_001],
    ['a future route', 'floor-state-2', '/m/pick/PICK%3A1', now + 1],
    ['an unversioned route', 'floor-state-2', '/m/pick/PICK%3A1', undefined],
  ])('rejects %s from pending authentication state', async (_case, callbackState, route, createdAt) => {
    window.sessionStorage.setItem(
      pendingRouteKey,
      JSON.stringify({ oauthState: 'floor-state-2', route, createdAt }),
    )
    window.history.replaceState({}, '', `/m/?state=${callbackState}&code=authorization-code`)
    keycloakMock.authenticated = true

    const callback = await loadKeycloak()
    window.history.replaceState({}, '', '/m/')
    callback.restoreAuthenticationRoute(callbackState, now)

    expect(window.location.pathname).toBe('/m/')
    expect(window.sessionStorage.getItem(pendingRouteKey)).toBeNull()
  })
})
