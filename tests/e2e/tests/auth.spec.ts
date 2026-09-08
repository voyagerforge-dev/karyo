/**
 * Authentication E2E tests.
 *
 * Verifies real Keycloak login flow, dashboard access after login,
 * role-based nav visibility, and logout.
 */
import { test, expect, keycloakLogin } from "../fixtures/auth";
import { logoutE2EUserSessions } from "../fixtures/db-cleanup";
import { defaultBaseUrl, TEST_DATA } from "../fixtures/test-data";

// Sidebar navigation area (shadcn sidebar). Scoping link lookups here avoids
// strict-mode collisions with same-named breadcrumb links in the header.
const SIDEBAR_NAV = '[data-sidebar="content"]';

test.describe("Authentication", () => {
  test("admin user can log in via Keycloak and sees dashboard", async ({
    authenticatedPage,
  }) => {
    // After login, should be on the dashboard (root path)
    await expect(authenticatedPage).toHaveURL(/\/$/);

    // Dashboard heading should be visible
    await expect(
      authenticatedPage.getByRole("heading", { name: "Operations Control" }),
    ).toBeVisible();
  });

  test("admin sees all nav items including Users", async ({
    authenticatedPage,
  }) => {
    // Wait for the app shell to render before asserting on the sidebar
    await expect(
      authenticatedPage.getByRole("heading", { name: "Operations Control" }),
    ).toBeVisible();

    const nav = authenticatedPage.locator(SIDEBAR_NAV);
    for (const item of TEST_DATA.adminNavItems) {
      await expect(
        nav.getByRole("link", { name: item, exact: true }),
      ).toBeVisible();
    }
  });

  test("operator user does NOT see Users nav item", async ({
    page,
    baseURL,
  }) => {
    // Login as operator -- the shared helper handles the workspace selector
    // card the SPA shows before redirecting to Keycloak.
    await keycloakLogin(
      page,
      defaultBaseUrl(baseURL),
      TEST_DATA.operator.username,
      TEST_DATA.operator.password,
    );
    await expect(
      page.getByRole("heading", { name: "Operations Control" }),
    ).toBeVisible();

    const nav = page.locator(SIDEBAR_NAV);

    // Operator should NOT see the Users nav link
    await expect(
      nav.getByRole("link", { name: "Users", exact: true }),
    ).not.toBeVisible();

    // But should see other nav items
    for (const item of TEST_DATA.operatorNavItems) {
      await expect(
        nav.getByRole("link", { name: item, exact: true }),
      ).toBeVisible();
    }
  });

  test("desktop login and expired-session reauthentication preserve deep links", async ({
    page,
    baseURL,
  }) => {
    const base = defaultBaseUrl(baseURL);
    const submitLogin = async () => {
      await page.waitForSelector("#username", { timeout: 30_000 });
      await page.fill("#username", TEST_DATA.operator.username);
      await page.fill("#password", TEST_DATA.operator.password);
      await page.click("#kc-login");
    };

    const firstRoute = "/inventory?scope=all#initial-login";
    await page.goto(`${base}${firstRoute}`);
    await submitLogin();
    await expect(page).toHaveURL(`${base}${firstRoute}`, { timeout: 30_000 });
    await expect(page.getByRole("heading", { name: "Inventory" })).toBeVisible();

    const reauthenticationRoute = "/inventory?scope=all#session-expired";
    await page.evaluate((route) => window.history.replaceState(window.history.state, "", route), reauthenticationRoute);
    await logoutE2EUserSessions(TEST_DATA.operator.username);
    await page.clock.setFixedTime(Date.now() + 60 * 60 * 1000);
    // An in-flight inventory request may start reauthentication before Export is
    // activated. Accept only that expected login screen, not a click timeout itself.
    await page.getByTestId("export-csv-btn").click({ timeout: 5_000 }).catch(async () => {
      await expect(page.locator("#username")).toBeVisible({ timeout: 30_000 });
    });

    await submitLogin();
    await expect(page).toHaveURL(`${base}${reauthenticationRoute}`, { timeout: 30_000 });
    await expect(page.getByRole("heading", { name: "Inventory" })).toBeVisible();
  });

  test("floor login and reauthentication preserve guarded deep links", async ({
    page,
    baseURL,
  }) => {
    const base = defaultBaseUrl(baseURL);
    const submitFloorLogin = async () => {
      await page.getByText("Log in", { exact: true }).click();
      await page.waitForSelector("#username", { timeout: 30_000 });
      await page.fill("#username", TEST_DATA.operator.username);
      await page.fill("#password", TEST_DATA.operator.password);
      await page.click("#kc-login");
    };

    const firstRoute = "/m/inquiry?mode=scan#lookup";
    await page.goto(`${base}${firstRoute}`);
    await submitFloorLogin();
    await expect(page).toHaveURL(`${base}${firstRoute}`, { timeout: 30_000 });
    await expect(page.getByRole("heading", { name: "INQUIRY" })).toBeVisible();

    // Exercise a reload controlled by the shipped PWA worker, not just first-load auth.
    // Its navigation fallback must leave the query-bearing silent SSO callback alone.
    await expect.poll(
      () => page.evaluate(() => navigator.serviceWorker.controller !== null),
      { timeout: 30_000 },
    ).toBe(true);
    await page.goto(`${base}/m/`);
    await page.getByRole("button", { name: "Sign out" }).click();
    await expect(page.getByText("Log in", { exact: true })).toBeVisible({ timeout: 30_000 });

    const secondRoute = "/m/inquiry?mode=rescan#reauthenticated";
    await page.goto(`${base}${secondRoute}`);
    await submitFloorLogin();
    await expect(page).toHaveURL(`${base}${secondRoute}`, { timeout: 30_000 });
    await expect(page.getByRole("heading", { name: "INQUIRY" })).toBeVisible();
  });

  test("logging out returns to the Keycloak login form", async ({
    authenticatedPage,
  }) => {
    // Logout is no longer a top-level sidebar button -- it's a
    // DropdownMenuItem inside the header's user menu (app-header.tsx). The
    // trigger has no accessible name/testid of its own; it's the one header
    // button whose visible text includes the logged-in username.
    const userMenuTrigger = authenticatedPage
      .locator("header")
      .getByRole("button")
      .filter({ hasText: TEST_DATA.admin.username });
    await userMenuTrigger.click();
    await authenticatedPage.getByRole("menuitem", { name: "Logout" }).click();

    // keycloak.logout() redirects through Keycloak's end_session endpoint and
    // back to the SPA origin (redirectUri: window.location.origin). The
    // tenant/workspace selector page was deleted 2026-07-18 -- Keycloak is
    // now configured with onLoad:'login-required' (see fixtures/auth.ts), so
    // an unauthenticated visit immediately re-redirects to the Keycloak
    // hosted login form. There is no app-side "/login" route to land on.
    await authenticatedPage.waitForSelector("#username", { timeout: 30_000 });
    await expect(authenticatedPage.locator("#username")).toBeVisible();
  });
});
