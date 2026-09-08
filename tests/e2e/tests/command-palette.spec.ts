/**
 * ⌘K command palette E2E (v1.2 sub-phase 2.0).
 *
 * Covers: open via Ctrl+K, Pages group present, product quick-search over
 * sample data, Escape closes. Loads sample data first (idempotent
 * fetch-or-create, same machinery as sample-data.spec.ts).
 *
 * Consolidation-sprint Task 3b (2026-07-21): the "Load Sample Data" button
 * this spec depends on had been orphaned (see sample-data.spec.ts and
 * `SampleDataCard`'s header comment) and was remounted on `/`. Getting this
 * spec green after that surfaced two more real, pre-existing issues:
 *
 * 1. Tenant visibility: `karyo-demo` hardcodes `CLIENT_ID = 1` (ACME) for
 *    every row it generates, and `POST /api/v1/demo/seed`/`reset` are
 *    `@RolesAllowed("ADMIN")` -- but `ItemData`/`StorageLocation` (and every
 *    other seeded entity) are `TenantEntity`, tenant-scoped by `client_id`.
 *    Verified live: after a successful seed, `admin` (client 0/SYS) sees 0
 *    products via `GET /api/v1/products`; `manager` (client 1/ACME) sees all
 *    24. So the *only* role that can trigger the seed can never see its own
 *    result through the normal tenant-scoped UI -- this spec now seeds via a
 *    direct API call as `admin`, then opens a SEPARATE `manager`-
 *    authenticated browser context to drive the actual ⌘K product-search
 *    assertions, mirroring the two-context pattern `floor-pick.spec.ts`
 *    already uses for the same admin-seeds/tenant-views split. (This also
 *    matches the documented workaround in project memory: "view demo as
 *    manager/manager -- admin=system tenant sees 0".)
 * 2. Seed non-idempotency: `InventoryGenerator.kt` is documented as "not
 *    idempotent by design ... re-seeding is expected to `reset` first" --
 *    calling `POST /api/v1/demo/seed` twice without a reset in between 500s
 *    on a `unit_loads.label_id` duplicate-key violation. This spec and
 *    `sample-data.spec.ts` both seed demo data and run several files apart
 *    in the suite with nothing resetting in between, so this spec resets
 *    before seeding (never depends on what an earlier spec left behind) and
 *    after seeding (never leaves data for `sample-data.spec.ts`, which comes
 *    later alphabetically, to collide with). `sample-data.spec.ts` itself is
 *    deliberately NOT changed this way -- its whole purpose is verifying
 *    seeding on a dirty/leftover-data DB, so pre-resetting there would erase
 *    the exact scenario it exists to test. The underlying non-idempotency
 *    remains a real, pre-existing backend gap -- out of scope to fix here
 *    (see the task report for detail); a *different* reset-list bug this
 *    investigation did fix in `DemoDataService.kt` was `item_units` (a
 *    durable Flyway seed row) being wiped by `reset()`, which broke any
 *    later spec creating a product via API before the next seed re-created
 *    it -- that one was a confirmed, narrowly-scoped, safe fix (preserving a
 *    reference table the function's own doc comment already promised to
 *    preserve), unlike the broader stock-placement idempotency question.
 *
 * The product quick-search term/expectations were also updated -- they
 * referenced the old frontend seeder's "Wireless Mouse"/"DEMO-MOUSE"
 * fixture, which the live `karyo-demo` backend generator does not create
 * (it creates `DEMO-SKU-01..NN` / "Demo Widget NN").
 *
 * Tasks 9-11 (defect-burndown, 2026-07-31): Load became destructive-with-
 * confirm on `SampleDataCard` (seed now auto-resets first -- see
 * `sample-data.spec.ts`'s header). Rather than let ⌘K bypass that confirm,
 * the two palette commands ("Load sample data…" / "Reset sample data…") no
 * longer call `loadSampleData()`/`resetSampleData()` at all: they simply
 * `navigate('/')` to the dashboard that hosts `SampleDataCard`, so the
 * card's own `AlertDialog` stays the single confirmed entry point (the "…"
 * suffix is the needs-further-interaction convention). Both commands are
 * also gated on `report-write` AND `useDemoEnabled()`, so they are absent
 * entirely when `KARYO_DEMO` is off. This spec never exercises those two
 * commands anyway (it seeds/resets via direct API calls, see below), so no
 * test changes were needed here.
 */
import { TEST_DATA } from "../fixtures/test-data";
import { test, expect } from "../fixtures/auth";
import { keycloakLogin } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";

/** Reset karyo-demo data (TRUNCATE CASCADE) -- safe to call even if nothing was ever seeded. */
async function resetDemoData(page: import("@playwright/test").Page): Promise<void> {
  const auth = await captureAuthHeader(page);
  const response = await page.request.post("/api/v1/demo/reset", {
    headers: { Authorization: auth },
  });
  expect(response.ok()).toBeTruthy();
}

test.describe("Command palette", () => {
  // Always leave demo data reset after this describe block's tests -- this
  // spec (unlike sample-data.spec.ts) owns cleaning up after itself so it
  // doesn't leave seeded data for a later spec to collide with (see header).
  test.afterEach(async ({ authenticatedPage }) => {
    await resetDemoData(authenticatedPage);
  });

  test("opens with Ctrl+K, lists pages, finds sample products, closes on Escape", async ({
    authenticatedPage: adminPage,
    browser,
    baseURL,
  }) => {
    test.setTimeout(120_000);

    // --- Seed via the karyo-demo engine (ADMIN-only) via direct API call ---
    // (the button-click UI flow is already covered end-to-end by
    // sample-data.spec.ts; this spec's actual subject is the ⌘K palette).
    await resetDemoData(adminPage);
    const auth = await captureAuthHeader(adminPage);
    const seedResponse = await adminPage.request.post("/api/v1/demo/seed", {
      headers: { Authorization: auth },
    });
    expect(seedResponse.ok()).toBeTruthy();

    // --- View as manager (ACME tenant) -- admin can never see client-1 data ---
    const base = baseURL || "http://localhost";
    const mgrCtx = await browser.newContext({ baseURL: base });
    const mgrPage = await mgrCtx.newPage();
    try {
      await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password);
      await mgrPage.goto("/");
      // Wait for the shell (and its global Ctrl+K keydown listener) to
      // mount before dispatching the shortcut -- a fresh context/page has no
      // prior focus/hydration warmup the way the shared authenticatedPage
      // fixture usually does by this point in a spec.
      await mgrPage
        .getByTestId("command-palette-trigger")
        .waitFor({ timeout: 15_000 });

      // --- Open with Ctrl+K (global shortcut) ---
      await mgrPage.keyboard.press("ControlOrMeta+k");
      const dialog = mgrPage.getByRole("dialog");
      await expect(dialog).toBeVisible({ timeout: 10_000 });

      // Pages group with role-visible nav entries
      await expect(dialog.getByText("Pages", { exact: true })).toBeVisible();
      await expect(dialog.getByText("Control", { exact: true })).toBeVisible();
      await expect(
        dialog.getByText("Inventory", { exact: true }),
      ).toBeVisible();

      // The copilot expectation-setter footer
      await expect(dialog.getByText("AI commands coming soon")).toBeVisible();

      // --- Product quick-search: type a sample-data product SKU ---
      await dialog.getByPlaceholder("Search or command…").fill("DEMO-SKU-01");
      await expect(
        dialog.getByText("Demo Widget 01", { exact: true }),
      ).toBeVisible({ timeout: 15_000 });
      await expect(dialog.getByText("DEMO-SKU-01")).toBeVisible();

      // --- Escape closes the palette ---
      await mgrPage.keyboard.press("Escape");
      await expect(dialog).not.toBeVisible();
    } finally {
      await mgrCtx.close();
    }
  });

  test("header search pill opens the palette", async ({
    authenticatedPage: page,
  }) => {
    await page.goto("/");
    await page.getByTestId("command-palette-trigger").click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    await expect(dialog.getByText("Pages", { exact: true })).toBeVisible();
    await page.keyboard.press("Escape");
    await expect(dialog).not.toBeVisible();
  });

  // Task 6 (defect-burndown): "Create product" used to deep-link to the dead
  // /products?create=1 route (404 catch-all). It now repoints to
  // /items?create=1, and the Items page opens the create sheet on that param.
  // Default authenticatedPage is the supplied admin credentials -- ADMIN carries product-write,
  // so the command is visible without any tenant-view gymnastics.
  test("Create product opens the Items create sheet at /items", async ({
    authenticatedPage: page,
  }) => {
    await page.goto("/");
    await page.getByTestId("command-palette-trigger").click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible({ timeout: 10_000 });

    await dialog.getByPlaceholder("Search or command…").fill("Create product");
    await dialog.getByText("Create product", { exact: true }).click();

    // The palette closes and the Items create sheet opens -- both are
    // role="dialog" (CommandDialog and the Sheet's Radix content share the
    // role), so assert on the palette's own search input disappearing
    // rather than re-querying getByRole('dialog') after the swap.
    await expect(
      page.getByPlaceholder("Search or command…"),
    ).not.toBeVisible();
    await expect(page).toHaveURL(/\/items(?:\?.*)?$/);
    await expect(
      page.getByRole("heading", { name: "New item" }),
    ).toBeVisible();
  });
});
