import Keycloak, { type KeycloakInitOptions } from 'keycloak-js'

const raw = (window as Window & { __ENV__?: Record<string, string> }).__ENV__?.KEYCLOAK_URL
const keycloakUrl = raw && !raw.includes('${') ? raw : (import.meta.env.VITE_KEYCLOAK_URL || '/auth')

export const keycloak = new Keycloak({ url: keycloakUrl, realm: 'karyo', clientId: 'karyo-web' })

export const appRedirectUri = `${window.location.origin}/m/`
export const pendingRouteKey = 'karyo.floor.auth.pending-route'
export const pendingRouteMaxAgeMs = 5 * 60 * 1000

interface PendingAuthenticationRoute {
  oauthState: string
  route: string
  createdAt: number
}

function removePendingRoute(): string | null {
  try {
    const pending = window.sessionStorage.getItem(pendingRouteKey)
    window.sessionStorage.removeItem(pendingRouteKey)
    return pending
  } catch {
    return null
  }
}

function floorRoute(route: unknown): string | null {
  if (typeof route !== 'string' || !route.startsWith('/m/') || route.startsWith('//')) return null
  try {
    const resolved = new URL(route, window.location.origin)
    if (resolved.origin !== window.location.origin || !resolved.pathname.startsWith('/m/')) return null
    return `${resolved.pathname}${resolved.search}${resolved.hash}`
  } catch {
    return null
  }
}

export async function createLoginRedirect(intendedRoute?: string): Promise<string> {
  const currentRoute = `${window.location.pathname}${window.location.search}${window.location.hash}`
  const candidate = floorRoute(intendedRoute ?? currentRoute)
  const route = candidate && new URL(candidate, window.location.origin).pathname !== '/m/login'
    ? candidate
    : new URL(appRedirectUri).pathname
  const loginUrl = await keycloak.createLoginUrl({ redirectUri: appRedirectUri })
  const oauthState = new URL(loginUrl, window.location.origin).searchParams.get('state')
  if (!oauthState) throw new Error('Keycloak login URL omitted OAuth state')

  try {
    window.sessionStorage.setItem(pendingRouteKey, JSON.stringify({ oauthState, route, createdAt: Date.now() }))
  } catch (error) {
    console.warn('[Keycloak] Could not preserve the floor route:', error)
  }
  return loginUrl
}

let pendingLogin: Promise<void> | undefined

/**
 * Starts one OAuth transaction, coalescing concurrent callers onto it.
 *
 * The floor app can reach here twice at once -- an operator tapping "Log in" while an expiring
 * session's `refreshSession` catch also starts one. Without the latch each call awaits its own
 * `createLoginUrl` and both write `{oauthState, route}` over the same sessionStorage key before
 * either navigates, so the browser can leave carrying state A while storage holds state B,
 * `restoreAuthenticationRoute` sees a mismatch, and the operator silently lands on `/m/` instead
 * of the pick they were on. First caller wins, and its stored route is the one restored.
 */
export async function startLogin(intendedRoute?: string): Promise<void> {
  if (!navigator.onLine) return
  pendingLogin ??= createLoginRedirect(intendedRoute)
    .then((loginUrl) => {
      window.location.assign(loginUrl)
    })
    .finally(() => {
      pendingLogin = undefined
    })
  return pendingLogin
}

export async function refreshSession(minValidity: number): Promise<boolean> {
  const hadSession = keycloak.authenticated === true
  try {
    return await keycloak.updateToken(minValidity)
  } catch (error) {
    if (hadSession && !keycloak.authenticated && navigator.onLine) await startLogin()
    throw error
  }
}

function oauthCallbackState(params: URLSearchParams): string | null {
  const state = params.get('state')
  const result = params.get('code') ?? params.get('error')
  return state && result ? state : null
}

export function readAuthenticationCallbackState(href = window.location.href): string | null {
  const callback = new URL(href, window.location.origin)
  return oauthCallbackState(callback.searchParams)
    ?? oauthCallbackState(new URLSearchParams(callback.hash.slice(1)))
}

export function restoreAuthenticationRoute(
  callbackState: string | null,
  now = Date.now(),
): void {
  if (!callbackState || !keycloak.authenticated) return

  const serialized = removePendingRoute()
  let destination = '/m/'
  if (serialized) {
    try {
      const pending = JSON.parse(serialized) as Partial<PendingAuthenticationRoute>
      const age = typeof pending.createdAt === 'number' ? now - pending.createdAt : -1
      if (
        pending.oauthState === callbackState &&
        age >= 0 &&
        age <= pendingRouteMaxAgeMs
      ) {
        destination = floorRoute(pending.route) ?? '/m/'
      }
    } catch {
      destination = '/m/'
    }
  }
  window.history.replaceState(window.history.state, '', destination)
}

export function endSession(): void {
  removePendingRoute()
  void keycloak.logout({ redirectUri: appRedirectUri })
}

/**
 * `responseMode: 'query'` overrides keycloak-js's `fragment` default, deliberately.
 *
 * A fragment never reaches JavaScript through `URLSearchParams` on `window.location.search`,
 * which is where `restoreAuthenticationRoute` reads the OAuth `state` it binds the pending floor
 * route to; query mode is what closed that defect (review-10) and it is the RFC 6749 default for
 * the authorization-code flow. It also means the authorization code traverses the query string,
 * so the reverse proxy redacts sensitive query values from its access log -- see the
 * `log_format` comment in infrastructure/docker/nginx/nginx.conf before widening either side.
 */
export const initOptions: KeycloakInitOptions = {
  onLoad: 'check-sso',
  flow: 'standard',
  pkceMethod: 'S256',
  responseMode: 'query',
  redirectUri: appRedirectUri,
  silentCheckSsoRedirectUri: `${window.location.origin}/m/silent-check-sso.html`,
  checkLoginIframe: false,
}
