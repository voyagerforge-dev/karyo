import type { Page } from '@playwright/test'

/**
 * Floor PWA login helper for Playwright E2E tests.
 *
 * The floor PWA at /m renders a Log in button after its silent session check. Tapping it
 * starts the Keycloak PKCE redirect. The fixed /m/ callback restores the requested route;
 * this helper starts at the inbox.
 */
export async function mobileLogin(
  page: Page,
  baseURL: string,
  username: string,
  password: string,
): Promise<void> {
  await page.goto(`${baseURL}/m/`)
  await page.getByRole('button', { name: 'Log in', exact: true }).click()

  // Fill the Keycloak login form.
  await page.waitForSelector('#username', { timeout: 30_000 })
  await page.fill('#username', username)
  await page.fill('#password', password)
  await page.click('#kc-login')

  // Keycloak redirects back to /m/; keycloak.init processes the code (authenticated=true),
  // restoreAuthenticationRoute lands the route and InboxHome renders.
  await page.waitForSelector('text=GET NEXT TASK', { timeout: 30_000 })
}
