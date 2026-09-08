/**
 * Sample-data re-runnability regression test (successor of DASH-10).
 *
 * Replicates an unsupervised client clicking "Load Sample Data" with NO
 * database cleanup: whatever state the DB is in, loading must work.
 * Covers: Load Sample Data -> loaded -> demo data visible in the UI ->
 * Reset -> Load AGAIN succeeds (idempotent fetch-or-create guarantee).
 *
 * IMPORTANT: this spec must NOT import the db-cleanup fixture -- surviving
 * leftover demo data (and arbitrary unrelated rows) is exactly what it
 * verifies.
 *
 * Consolidation-sprint Task 3b (2026-07-21): `SampleDataCard` had been
 * orphaned (unmounted from the dashboard by the June Control redesign) and
 * was remounted on Operations Control (`/`) -- see that component's header
 * comment. While fixing that, this spec's post-load data assertions were
 * also updated: they still referenced the OLD frontend-driven seeder's
 * fixtures ("DEMO-MOUSE"/"Wireless Mouse" product, "Receiving Dock" as a
 * flat locations-page row) and the retired `/products` table route. The
 * live backend seeder is the `karyo-demo` module (`CatalogGenerator.kt`),
 * which creates SKUs numbered `DEMO-SKU-01..NN` (names "Demo Widget NN")
 * and locations named `LOC-01..NN` -- "Receiving Dock" is a *zone* name in
 * that generator, and the current master-detail `/locations` page lists
 * flat storage locations only (no zone rows), so the zone name was replaced
 * with a real location row assertion instead.
 *
 * Also confirmed live (see `command-palette.spec.ts`'s header comment for
 * full detail): `karyo-demo` seeds everything under `client_id=1` (ACME),
 * but only `admin` (`client_id=0`/SYS) can call the `POST /api/v1/demo/*`
 * endpoints (`@RolesAllowed("ADMIN")`) -- and `admin` can never SEE that
 * data afterward through the normal tenant-scoped UI (`ItemData`/
 * `StorageLocation` are `TenantEntity`). The button-click flow itself (Load
 * -> loaded -> Reset -> Load again) still runs on the `admin` page, since
 * that's the only role that can drive it and this spec's core subject is
 * exactly that state-machine, not the resulting data's visibility -- but
 * verifying the *data* landed (the "surfaces demo data" step) now opens a
 * separate `manager`-authenticated context, the same split
 * `floor-pick.spec.ts` already uses for its own admin-seeds/tenant-executes
 * scenario.
 *
 * Task 9 (defect-burndown, 2026-07-31): `POST /api/v1/demo/seed` now resets
 * the demo warehouse first so it is safe to call twice in a row (previously
 * a second seed 500'd on a `unit_loads` duplicate key -- fixed at
 * `DemoResource.seed`). Load became destructive as a result and now sits
 * behind the same `AlertDialog` confirm idiom Reset already used, so the
 * `loadSampleData` helper below clicks the trigger AND the dialog's confirm
 * button.
 */
import { TEST_DATA } from "../fixtures/test-data";
import type { Page } from "@playwright/test";
import { test, expect, keycloakLogin } from "../fixtures/auth";

/**
 * Click Load Sample Data, confirm the destructive AlertDialog (Task 9,
 * defect-burndown: seed now auto-resets first, so the button is
 * confirm-gated the same way Reset already is), and wait until the card
 * shows the loaded state.
 */
async function loadSampleData(page: Page) {
  await page.goto("/");
  const loadBtn = page.getByRole("button", { name: "Load Sample Data" });
  await expect(loadBtn).toBeVisible({ timeout: 15_000 });
  await loadBtn.click();

  const dialog = page.getByRole("alertdialog");
  await expect(dialog.getByText("Load sample data?")).toBeVisible({
    timeout: 5_000,
  });
  await dialog.getByRole("button", { name: "Load Sample Data" }).click();

  await expect(page.getByText("Sample Data Loaded")).toBeVisible({
    timeout: 90_000,
  });
}

test.describe("Sample data", () => {
  test("loads, surfaces demo data, resets, and loads again on a dirty database", async ({
    authenticatedPage: page,
    browser,
    baseURL,
  }) => {
    test.setTimeout(420_000);

    // --- Pass 1: Load Sample Data on whatever state the DB is in ---
    await loadSampleData(page);

    // The seeded data is tenant-scoped to client_id=1 (ACME); admin (the
    // only role that can trigger the seed) can never see it -- verify from a
    // separate manager-authenticated context instead (see header comment).
    const base = baseURL || "http://localhost";
    const mgrCtx = await browser.newContext({ baseURL: base });
    const mgrPage = await mgrCtx.newPage();
    try {
      await keycloakLogin(mgrPage, base, TEST_DATA.manager.username, TEST_DATA.manager.password);

      // The demo SKUs must be visible in the /items master-detail list.
      await mgrPage.goto("/items");
      await mgrPage
        .getByPlaceholder("Search SKU, name, category…")
        .fill("DEMO-SKU-01");
      await expect(
        mgrPage.locator("button").filter({ hasText: "DEMO-SKU-01" }),
      ).toBeVisible({ timeout: 15_000 });

      // The demo locations must be visible in the /locations master-detail list.
      await mgrPage.goto("/locations");
      await mgrPage.getByPlaceholder(/search bin or zone/i).fill("LOC-01");
      await expect(
        mgrPage.locator("button").filter({ hasText: "LOC-01" }),
      ).toBeVisible({ timeout: 15_000 });
    } finally {
      await mgrCtx.close();
    }

    // --- Reset via the card (app-level reset, not DB cleanup) ---
    await page.goto("/");
    await page.getByRole("button", { name: "Reset", exact: true }).click();
    const confirmButton = page.getByRole("button", {
      name: "Reset Sample Data",
    });
    await expect(confirmButton).toBeVisible({ timeout: 5_000 });
    await confirmButton.click();
    await expect(
      page.getByRole("button", { name: "Load Sample Data" }),
    ).toBeVisible({ timeout: 120_000 });

    // --- Pass 2: Load AGAIN -- must complete on the now-dirty DB ---
    await loadSampleData(page);
  });
});
