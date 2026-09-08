import { test as base, type Page } from "@playwright/test";
import { defaultBaseUrl, E2E_PROVISIONED_USERNAMES, TEST_DATA } from "./test-data";

/**
 * Keycloak login helper fixture for Playwright E2E tests.
 *
 * Uses real Keycloak login flow (no token injection) -- navigates to baseURL,
 * waits for Keycloak redirect, fills credentials, and waits for redirect back.
 */

type Credentials = { username: string; password: string };

type AuthFixtures = {
  authenticatedPage: Page;
  /** Role names resolve only on use, so collection needs no credentials. Null means admin. */
  credentials: Credentials | Lowercase<keyof typeof E2E_PROVISIONED_USERNAMES> | null;
};

export async function keycloakLogin(
  page: Page,
  baseURL: string,
  username: string,
  password: string,
): Promise<void> {
  // Navigate to app -- the tenant-selection screen was deleted (2026-07-18);
  // Keycloak is now configured with onLoad:'login-required', so the SPA
  // redirects straight to the Keycloak login form.
  await page.goto(baseURL);

  // Wait for Keycloak login form to appear
  await page.waitForSelector("#username", { timeout: 30_000 });

  // Fill credentials and submit
  await page.fill("#username", username);
  await page.fill("#password", password);
  await page.click("#kc-login");

  // Wait for redirect back to the application
  await page.waitForURL((url) => !url.href.includes("/realms/"), {
    timeout: 30_000,
  });
}

export const test = base.extend<AuthFixtures>({
  credentials: [null, { option: true }],

  authenticatedPage: async ({ page, credentials, baseURL }, use) => {
    const selected = credentials ?? "admin";
    const { username, password } = typeof selected === "string" ? TEST_DATA[selected] : selected;
    await keycloakLogin(page, defaultBaseUrl(baseURL), username, password);
    await use(page);
  },
});

export { expect } from "@playwright/test";
