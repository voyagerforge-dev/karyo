import { beforeEach, describe, expect, it, vi } from 'vitest';

const keycloakMock = vi.hoisted(() => ({
  authenticated: false,
  createLoginUrl: vi.fn<() => Promise<string>>(),
  updateToken: vi.fn<(minValidity: number) => Promise<boolean>>(),
  logout: vi.fn(),
  instance: undefined as { authenticated: boolean } | undefined,
}));

vi.mock('keycloak-js', () => ({
  default: class MockKeycloak {
    get authenticated() { return keycloakMock.authenticated; }
    set authenticated(value: boolean) { keycloakMock.authenticated = value; }
    createLoginUrl = keycloakMock.createLoginUrl;
    updateToken = keycloakMock.updateToken;
    logout = keycloakMock.logout;
    constructor() {
      keycloakMock.instance = this;
    }
  },
}));

const storageKey = 'karyo.auth.pending-route';
const now = 2_000_000;

async function loadKeycloak() {
  return import('@/lib/keycloak');
}

beforeEach(() => {
  vi.restoreAllMocks();
  vi.resetModules();
  keycloakMock.authenticated = false;
  keycloakMock.createLoginUrl.mockReset();
  keycloakMock.updateToken.mockReset();
  keycloakMock.logout.mockReset();
  window.sessionStorage.clear();
  window.history.replaceState({}, '', '/');
});

describe('desktop Keycloak route transactions', () => {
  // Adapter-level proof: exact `/` callback, OAuth-state-bound restore, consume-once,
  // reject malformed/cross-origin/stale/replayed destinations, fall back to `/`.
  // Does not authenticate through Keycloak and does not prove the production-realm
  // browser callback contract. That full-browser proof is follow-up work against
  // the Keycloak production realm task.
  it('restores a deep route bound to the successful OAuth state', async () => {
    window.history.replaceState({}, '', '/orders/123?tab=lines#allocations');
    keycloakMock.createLoginUrl.mockResolvedValue(
      '/auth/realms/karyo/protocol/openid-connect/auth?client_id=karyo-web&state=oauth-state-1',
    );

    const login = await loadKeycloak();
    vi.spyOn(Date, 'now').mockReturnValueOnce(now);
    await expect(login.createLoginRedirect()).resolves.toContain('state=oauth-state-1');
    expect(login.initOptions.responseMode).toBe('query');
    expect(keycloakMock.createLoginUrl).toHaveBeenCalledWith({
      redirectUri: `${window.location.origin}/`,
    });
    expect(JSON.parse(window.sessionStorage.getItem(storageKey)!)).toEqual({
      oauthState: 'oauth-state-1',
      route: '/orders/123?tab=lines#allocations',
      createdAt: now,
    });

    window.history.replaceState({}, '', '/#state=oauth-state-1&code=authorization-code');
    expect(login.readAuthenticationCallbackState()).toBe('oauth-state-1');
    keycloakMock.authenticated = true;
    login.restoreAuthenticationRoute('oauth-state-1', now + 1_000);

    expect(`${window.location.pathname}${window.location.search}${window.location.hash}`)
      .toBe('/orders/123?tab=lines#allocations');
    expect(window.sessionStorage.getItem(storageKey)).toBeNull();
  });

  it.each([
    ['mismatched state', { oauthState: 'other', route: '/orders/1', createdAt: now }],
    ['cross-origin route', { oauthState: 'state', route: '//evil.example/orders/1', createdAt: now }],
    ['absolute route', { oauthState: 'state', route: 'https://evil.example/orders/1', createdAt: now }],
    ['stale route', { oauthState: 'state', route: '/orders/1', createdAt: now - 300_001 }],
    ['future route', { oauthState: 'state', route: '/orders/1', createdAt: now + 1 }],
    ['unversioned route', { oauthState: 'state', route: '/orders/1' }],
  ])('consumes and rejects a %s', async (_label, pending) => {
    window.sessionStorage.setItem(storageKey, JSON.stringify(pending));
    window.history.replaceState({}, '', '/?state=state&code=authorization-code');
    keycloakMock.authenticated = true;
    const callback = await loadKeycloak();

    callback.restoreAuthenticationRoute('state', now);

    expect(`${window.location.pathname}${window.location.search}${window.location.hash}`).toBe('/');
    expect(window.sessionStorage.getItem(storageKey)).toBeNull();
  });

  it('rejects a replay after the successful destination was consumed', async () => {
    window.sessionStorage.setItem(storageKey, JSON.stringify({
      oauthState: 'state', route: '/inventory', createdAt: now,
    }));
    window.history.replaceState({}, '', '/?state=state&code=first');
    keycloakMock.authenticated = true;
    const callback = await loadKeycloak();
    callback.restoreAuthenticationRoute('state', now);
    expect(window.location.pathname).toBe('/inventory');

    window.history.replaceState({}, '', '/?state=state&code=replayed');
    callback.restoreAuthenticationRoute('state', now + 1);

    expect(window.location.pathname).toBe('/');
  });

  it('falls back safely when stored state is malformed', async () => {
    window.sessionStorage.setItem(storageKey, '{not-json');
    window.history.replaceState({}, '', '/?state=state&code=authorization-code');
    keycloakMock.authenticated = true;
    const callback = await loadKeycloak();

    callback.restoreAuthenticationRoute('state', now);

    expect(window.location.pathname).toBe('/');
    expect(window.sessionStorage.getItem(storageKey)).toBeNull();
  });

  it('preserves an authenticated application route with a state filter', async () => {
    window.history.replaceState({}, '', '/orders?state=300');
    window.sessionStorage.setItem(storageKey, JSON.stringify({
      oauthState: '300', route: '/inventory', createdAt: now,
    }));
    keycloakMock.authenticated = true;
    const callback = await loadKeycloak();

    const callbackState = callback.readAuthenticationCallbackState();
    callback.restoreAuthenticationRoute(callbackState, now);

    expect(callbackState).toBeNull();
    expect(`${window.location.pathname}${window.location.search}`).toBe('/orders?state=300');
    expect(window.sessionStorage.getItem(storageKey)).not.toBeNull();
  });

  it('reads state from OAuth success and error callbacks only', async () => {
    const callback = await loadKeycloak();

    expect(callback.readAuthenticationCallbackState('/?state=success&code=authorization-code'))
      .toBe('success');
    expect(callback.readAuthenticationCallbackState('/#state=denied&error=access_denied'))
      .toBe('denied');
    expect(callback.readAuthenticationCallbackState('/?state=filter&code='))
      .toBeNull();
  });

  it('coalesces concurrent logins onto one OAuth transaction', async () => {
    // An expiring session reaches startLogin twice at once: AuthGuard's effect via onAuthLogout,
    // and refreshSession's catch. Two transactions would overwrite the single pending-route key
    // between the first setItem and the first navigation, stranding the operator on '/'.
    window.history.replaceState({}, '', '/orders/123?tab=lines#allocations');
    let issued = 0;
    keycloakMock.createLoginUrl.mockImplementation(async () => {
      issued += 1;
      return `https://identity.example.test/authorize?client_id=karyo-web&state=oauth-state-${issued}`;
    });

    const login = await loadKeycloak();
    vi.spyOn(Date, 'now').mockReturnValue(now);
    await Promise.all([login.startLogin(), login.startLogin()]);

    expect(keycloakMock.createLoginUrl).toHaveBeenCalledTimes(1);
    expect(JSON.parse(window.sessionStorage.getItem(storageKey)!)).toEqual({
      oauthState: 'oauth-state-1',
      route: '/orders/123?tab=lines#allocations',
      createdAt: now,
    });

    // The only login URL ever generated carried oauth-state-1, so that is what the browser was
    // sent with -- and the deep route survives the round trip instead of falling back to '/'.
    window.history.replaceState({}, '', '/?state=oauth-state-1&code=authorization-code');
    keycloakMock.authenticated = true;
    vi.resetModules();
    const callback = await loadKeycloak();
    window.history.replaceState({}, '', '/');

    callback.restoreAuthenticationRoute('oauth-state-1', now + 1_000);

    expect(`${window.location.pathname}${window.location.search}${window.location.hash}`)
      .toBe('/orders/123?tab=lines#allocations');
  });

  it.each([
    ['offline token refresh', false, false],
    ['transient online refresh failure', true, true],
  ])('does not leave the desktop app after %s', async (_case, online, authenticated) => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(online);
    keycloakMock.authenticated = authenticated;
    keycloakMock.updateToken.mockRejectedValue(new Error('identity service unavailable'));

    const session = await loadKeycloak();

    await expect(session.refreshSession(60)).rejects.toThrow('identity service unavailable');
    expect(keycloakMock.createLoginUrl).not.toHaveBeenCalled();
  });

  it('starts reauthentication when an established online session expires', async () => {
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true);
    keycloakMock.authenticated = true;
    keycloakMock.createLoginUrl.mockRejectedValue(new Error('login redirect observed'));

    const session = await loadKeycloak();
    keycloakMock.updateToken.mockImplementation(async () => {
      // What keycloak-js clearToken() does when the refresh endpoint answers 400.
      keycloakMock.instance!.authenticated = false;
      throw new Error('refresh token expired');
    });

    await expect(session.refreshSession(60)).rejects.toThrow('login redirect observed');
    expect(keycloakMock.createLoginUrl).toHaveBeenCalledWith({
      redirectUri: `${window.location.origin}/`,
    });
  });

  it('does not redirect a caller that never had a session', async () => {
    // keycloak-js rejects before any network call when there is no refresh token, so there is no
    // session to re-establish and nothing that a forced redirect could recover.
    vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true);
    keycloakMock.authenticated = false;
    keycloakMock.updateToken.mockRejectedValue(
      new Error('Unable to update token, no refresh token available.'),
    );

    const session = await loadKeycloak();

    await expect(session.refreshSession(60)).rejects.toThrow('no refresh token available');
    expect(keycloakMock.createLoginUrl).not.toHaveBeenCalled();
  });
});
