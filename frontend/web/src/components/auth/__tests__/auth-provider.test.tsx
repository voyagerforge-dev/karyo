import { act, fireEvent, render, screen } from '@testing-library/react';
import { MemoryRouter, Route, Routes } from 'react-router';
import { afterEach, beforeEach, expect, test, vi } from 'vitest';

/**
 * Only keycloak-js is mocked. Mocking '@/lib/keycloak' instead would stub out refreshSession --
 * the one place the expiry-vs-network discrimination lives -- and the provider never calls
 * startLogin itself, so the assertion below would hold for every possible implementation,
 * including one that signs an operator out on an ordinary network blip.
 */
const keycloakMock = vi.hoisted(() => ({
  authenticated: true,
  token: 'token',
  tokenParsed: {
    preferred_username: 'operator',
    realm_access: { roles: [] as string[] },
  } as Record<string, unknown> | undefined,
  createLoginUrl: vi.fn<() => Promise<string>>(),
  updateToken: vi.fn<(minValidity: number) => Promise<boolean>>(),
  logout: vi.fn(),
  instance: undefined as { authenticated: boolean } | undefined,
}));

vi.mock('keycloak-js', () => ({
  default: class MockKeycloak {
    authenticated = keycloakMock.authenticated;
    token = keycloakMock.token;
    tokenParsed = keycloakMock.tokenParsed;
    createLoginUrl = keycloakMock.createLoginUrl;
    updateToken = keycloakMock.updateToken;
    logout = keycloakMock.logout;
    constructor() {
      keycloakMock.instance = this;
    }
  },
}));

beforeEach(() => {
  vi.useFakeTimers();
  vi.resetModules();
  keycloakMock.authenticated = true;
  keycloakMock.createLoginUrl.mockReset();
  // Rejecting stops startLogin before window.location.assign, which jsdom will not let a test
  // replace. Whether the adapter reached for a login URL is the observable that matters here.
  keycloakMock.createLoginUrl.mockRejectedValue(new Error('login redirect observed'));
  keycloakMock.updateToken.mockReset();
  vi.spyOn(window.navigator, 'onLine', 'get').mockReturnValue(true);
});

afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
});

async function renderProvider() {
  const { AuthProvider } = await import('@/components/auth/auth-provider');
  render(<AuthProvider><p>Desktop workflow</p></AuthProvider>);
  await act(async () => {
    vi.advanceTimersByTime(30_000);
    await Promise.resolve();
    await Promise.resolve();
  });
}

test('transient periodic refresh failure keeps the desktop app open', async () => {
  keycloakMock.updateToken.mockRejectedValue(new Error('network unavailable'));

  await renderProvider();

  expect(keycloakMock.updateToken).toHaveBeenCalledWith(60);
  expect(keycloakMock.createLoginUrl).not.toHaveBeenCalled();
});

test('a genuinely expired session reauthenticates from the periodic refresh', async () => {
  keycloakMock.updateToken.mockImplementation(async () => {
    // What keycloak-js clearToken() does when the refresh endpoint answers 400.
    keycloakMock.instance!.authenticated = false;
    throw new Error('refresh token expired');
  });

  await renderProvider();

  expect(keycloakMock.createLoginUrl).toHaveBeenCalledWith({
    redirectUri: `${window.location.origin}/`,
  });
});

test('a failed login redirect surfaces a visible error instead of a blank guarded page', async () => {
  keycloakMock.authenticated = false;
  const { AuthProvider, LOGIN_REDIRECT_FAILED } = await import(
    '@/components/auth/auth-provider'
  );
  const { AuthGuard } = await import('@/components/auth/auth-guard');

  await act(async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <AuthProvider>
          <Routes>
            <Route element={<AuthGuard />}>
              <Route path="/" element={<p>Desktop workflow</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await Promise.resolve();
    await Promise.resolve();
  });

  expect(keycloakMock.createLoginUrl).toHaveBeenCalled();
  expect(screen.queryByText('Desktop workflow')).toBeNull();
  expect(screen.getByRole('alert')).toHaveTextContent(LOGIN_REDIRECT_FAILED);
});

test('the failed-login error state retries the login redirect on demand', async () => {
  keycloakMock.authenticated = false;
  const { AuthProvider } = await import('@/components/auth/auth-provider');
  const { AuthGuard } = await import('@/components/auth/auth-guard');

  await act(async () => {
    render(
      <MemoryRouter initialEntries={['/']}>
        <AuthProvider>
          <Routes>
            <Route element={<AuthGuard />}>
              <Route path="/" element={<p>Desktop workflow</p>} />
            </Route>
          </Routes>
        </AuthProvider>
      </MemoryRouter>,
    );
    await Promise.resolve();
    await Promise.resolve();
  });

  expect(keycloakMock.createLoginUrl).toHaveBeenCalledTimes(1);

  await act(async () => {
    fireEvent.click(screen.getByRole('button', { name: 'Try Again' }));
    await Promise.resolve();
    await Promise.resolve();
  });

  expect(keycloakMock.createLoginUrl).toHaveBeenCalledTimes(2);
});
