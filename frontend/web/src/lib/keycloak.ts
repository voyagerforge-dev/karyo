import Keycloak from 'keycloak-js';

/**
 * Resolve Keycloak URL with fallback chain:
 * 1. window.__ENV__.KEYCLOAK_URL (runtime injection via nginx envsubst)
 * 2. VITE_KEYCLOAK_URL (build-time env var for dev)
 * 3. same-origin /auth (production default)
 *
 * The includes('${') guard skips the envsubst placeholder when it hasn't been replaced.
 */
const raw = (window as Window & { __ENV__?: Record<string, string> }).__ENV__?.KEYCLOAK_URL;
const keycloakUrl = raw && !raw.includes('${')
  ? raw
  : import.meta.env.VITE_KEYCLOAK_URL || '/auth';

const keycloakRealm = 'karyo';
const keycloakClientId = 'karyo-web';

console.info('[Keycloak] Initializing with URL:', keycloakUrl, '| realm:', keycloakRealm, '| clientId:', keycloakClientId);

/**
 * Module-level Keycloak singleton.
 * Created outside React to survive StrictMode double-mount.
 * @see https://github.com/keycloak/keycloak/issues/19452
 */
export const keycloak = new Keycloak({
  url: keycloakUrl,
  realm: keycloakRealm,
  clientId: keycloakClientId,
});

export const appRedirectUri = `${window.location.origin}/`;
export const pendingRouteKey = 'karyo.auth.pending-route';
export const pendingRouteMaxAgeMs = 5 * 60 * 1000;

interface PendingAuthenticationRoute {
  oauthState: string;
  route: string;
  createdAt: number;
}

function removePendingRoute(): string | null {
  try {
    const pending = window.sessionStorage.getItem(pendingRouteKey);
    window.sessionStorage.removeItem(pendingRouteKey);
    return pending;
  } catch {
    return null;
  }
}

function localRoute(route: unknown): string | null {
  if (typeof route !== 'string' || !route.startsWith('/') || route.startsWith('//')) return null;
  try {
    const resolved = new URL(route, window.location.origin);
    if (resolved.origin !== window.location.origin) return null;
    return `${resolved.pathname}${resolved.search}${resolved.hash}`;
  } catch {
    return null;
  }
}

export async function createLoginRedirect(): Promise<string> {
  const route = `${window.location.pathname}${window.location.search}${window.location.hash}`;
  const loginUrl = await keycloak.createLoginUrl({ redirectUri: appRedirectUri });
  const oauthState = new URL(loginUrl, window.location.origin).searchParams.get('state');
  if (!oauthState) throw new Error('Keycloak login URL omitted OAuth state');

  const pending: PendingAuthenticationRoute = { oauthState, route, createdAt: Date.now() };
  try {
    window.sessionStorage.setItem(pendingRouteKey, JSON.stringify(pending));
  } catch (error) {
    console.warn('[Keycloak] Could not preserve the login route:', error);
  }
  return loginUrl;
}

let pendingLogin: Promise<void> | undefined;

/**
 * Starts one OAuth transaction, coalescing concurrent callers onto it.
 *
 * An expiring session triggers this twice: keycloak-js `clearToken` fires `onAuthLogout`
 * synchronously before `updateToken` rejects, so `AuthGuard`'s effect calls here, and then the
 * rejection reaches `refreshSession`'s catch, which calls here too. Without the latch each call
 * awaits its own `createLoginUrl`, and both write `{oauthState, route}` over the same
 * sessionStorage key before either navigates -- so the browser can leave carrying state A while
 * storage holds state B, `restoreAuthenticationRoute` sees a mismatch, and the operator silently
 * lands on `/` instead of the deep path. One transaction, one stored entry, one matching state.
 */
export async function startLogin(): Promise<void> {
  pendingLogin ??= createLoginRedirect()
    .then((loginUrl) => {
      window.location.assign(loginUrl);
    })
    .finally(() => {
      pendingLogin = undefined;
    });
  return pendingLogin;
}

/**
 * Refreshes the access token, starting login only when reauthentication is genuinely required.
 *
 * The three outcomes keycloak-js can produce are told apart by comparing `authenticated` across
 * the call. A transient network failure leaves it true (`clearToken` runs only on a 400 from the
 * refresh endpoint). A genuinely expired session flips it true -> false. A caller that never had
 * a session sees `updateToken` reject immediately with "no refresh token available", and `false`
 * on both sides: that operator needs a first login, not a forced *re*authentication, so pushing
 * them at Keycloak here would discard whatever route the guard had captured.
 */
export async function refreshSession(minValidity: number): Promise<boolean> {
  const hadSession = keycloak.authenticated === true;
  try {
    return await keycloak.updateToken(minValidity);
  } catch (error) {
    if (hadSession && !keycloak.authenticated && navigator.onLine) await startLogin();
    throw error;
  }
}

function oauthCallbackState(params: URLSearchParams): string | null {
  const state = params.get('state');
  const result = params.get('code') ?? params.get('error');
  return state && result ? state : null;
}

export function readAuthenticationCallbackState(href = window.location.href): string | null {
  const callback = new URL(href, window.location.origin);
  return oauthCallbackState(callback.searchParams)
    ?? oauthCallbackState(new URLSearchParams(callback.hash.slice(1)));
}

export function restoreAuthenticationRoute(
  callbackState: string | null,
  now = Date.now(),
): void {
  if (!callbackState || !keycloak.authenticated) return;

  const serialized = removePendingRoute();
  let destination = '/';
  if (serialized) {
    try {
      const pending = JSON.parse(serialized) as Partial<PendingAuthenticationRoute>;
      const age = typeof pending.createdAt === 'number' ? now - pending.createdAt : -1;
      if (
        pending.oauthState === callbackState &&
        age >= 0 &&
        age <= pendingRouteMaxAgeMs
      ) {
        destination = localRoute(pending.route) ?? '/';
      }
    } catch {
      destination = '/';
    }
  }
  window.history.replaceState(window.history.state, '', destination);
}

export function endSession(): void {
  removePendingRoute();
  void keycloak.logout({ redirectUri: appRedirectUri });
}

/**
 * `responseMode: 'query'` overrides keycloak-js's `fragment` default, deliberately.
 *
 * A fragment never reaches JavaScript through `URLSearchParams` on `window.location.search`,
 * which is where `restoreAuthenticationRoute` reads the OAuth `state` it binds the pending route
 * to; query mode is what closed that defect (review-10) and it is the RFC 6749 default for the
 * authorization-code flow. It also means the authorization code traverses the query string, so
 * the reverse proxy redacts sensitive query values from its access log -- see the `log_format`
 * comment in infrastructure/docker/nginx/nginx.conf before widening either side.
 */
export const initOptions: Keycloak.KeycloakInitOptions = {
  onLoad: 'check-sso',
  flow: 'standard',
  pkceMethod: 'S256',
  responseMode: 'query',
  redirectUri: appRedirectUri,
  silentCheckSsoRedirectUri: `${window.location.origin}/silent-check-sso.html`,
  checkLoginIframe: false,
};
