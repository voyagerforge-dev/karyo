/**
 * Locations E2E tests.
 *
 * /locations is Control master-detail: a single flat list of every location
 * (search + Pick face/Reserve/Blocked filter chips) on the left, a
 * read/action workspace pane (Edit/Replenish/Cycle count/Block) on the
 * right. There is no <table>, no drawer, and -- per the July redesign -- no
 * zone/area/cluster hierarchy drill-down or zone-creation UI at all: `New
 * location` is the only create affordance, and it always creates a flat
 * `locations` row (zone/cluster are optional dropdowns on the same form,
 * not separate levels to create). Location-locking sprint Task 10
 * (2026-07-25) wired the detail pane's Edit button: it swaps the pane into
 * `LocationForm` in edit mode (mirrors strategies-page's view/create/edit
 * pattern) -- capacity/temperatureZone/handlingClass/kind/plcCode/
 * isClearing/allocationState are all editable there now.
 *
 * RETIRED: "create a new zone via the form" and "create zone > area >
 * cluster > location hierarchy" -- both scenarios exercised UI affordances
 * (a "Create Zone"/"Create Area"/"Create Cluster" button, drill-down
 * navigation) that no longer exist anywhere in the current frontend
 * (confirmed by source read and by grepping frontend/web/src for those
 * button labels). `LocationForm` still supports create/edit at the
 * zones/areas/clusters levels in code, but `LocationsPage` never renders it
 * at those levels, so there is no live path to reach that code from the UI
 * -- only the `locations` level has a wired Edit trigger.
 */
import type { Page } from "@playwright/test";
import { test, expect } from "../fixtures/auth";
import { uniqueName } from "../fixtures/test-data";
import { captureAuthHeader } from "../fixtures/api";

const SEARCH_PLACEHOLDER = /search bin or zone/i;

test.describe("Locations", () => {
  // The create-location form derives a default `areaId` from an existing
  // location's area (see `defaultAreaId` in locations-page.tsx) -- admin is
  // SYS (client_id 0), which owns zero locations/areas, so that fallback is
  // always undefined and the form submits `areaId: 0`, which the backend
  // rejects. Same root cause as putaway/picking/packing/cycle-count/etc --
  // unit-load/location creation requires a real goods owner (clientId != 0).
  test.use({ credentials: "manager" });

  test.beforeEach(async ({ authenticatedPage }) => {
    await authenticatedPage.goto("/locations");
    await expect(authenticatedPage).toHaveURL(/\/locations$/);
  });

  test("locations page loads and shows the master-detail list", async ({
    authenticatedPage,
  }) => {
    await expect(
      authenticatedPage.getByTestId("locations-page"),
    ).toBeVisible();
    await expect(
      authenticatedPage.getByPlaceholder(SEARCH_PLACEHOLDER),
    ).toBeVisible({ timeout: 15_000 });
  });

  test("create a new location via the form and verify it appears", async ({
    authenticatedPage: page,
  }: {
    authenticatedPage: Page;
  }) => {
    const auth = await captureAuthHeader(page);

    // Location creation requires a location type, which has no management
    // UI of its own -- create one via the API (swept by global setup).
    const locationTypeName = uniqueName("lt");
    const ltResponse = await page.request.post("/api/v1/location-types", {
      headers: { Authorization: auth },
      data: { name: locationTypeName },
    });
    expect(ltResponse.ok()).toBeTruthy();

    const locationName = uniqueName("loc");

    await page.getByRole("button", { name: "New location" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    await expect(
      dialog.getByRole("heading", { name: "New location" }),
    ).toBeVisible();

    await dialog.getByLabel("Name", { exact: true }).fill(locationName);

    // Location Type is a Radix Select (role=combobox); its trigger shows the
    // placeholder text until a value is chosen.
    await dialog
      .getByRole("combobox")
      .filter({ hasText: /select location type/i })
      .click();
    await page.getByRole("option", { name: locationTypeName }).click();

    await dialog.getByRole("button", { name: "Save", exact: true }).click();
    await expect(dialog).toBeHidden({ timeout: 10_000 });

    // The new location should appear in the master list (code falls back to
    // name when no scan code was set -- see location-derive.ts).
    await page.getByPlaceholder(SEARCH_PLACEHOLDER).fill(locationName);
    const row = page.locator("button").filter({ hasText: locationName });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.click();
    await expect(
      page.getByRole("heading", { name: locationName }),
    ).toBeVisible();
  });

  test("edit a location's PLC code and kind via the Edit form", async ({
    authenticatedPage: page,
  }: {
    authenticatedPage: Page;
  }) => {
    const auth = await captureAuthHeader(page);

    const locationTypeName = uniqueName("lt");
    const ltResponse = await page.request.post("/api/v1/location-types", {
      headers: { Authorization: auth },
      data: { name: locationTypeName },
    });
    expect(ltResponse.ok()).toBeTruthy();

    const locationName = uniqueName("loc");
    const plcCode = uniqueName("plc");

    await page.getByRole("button", { name: "New location" }).click();
    const dialog = page.getByRole("dialog");
    await expect(dialog).toBeVisible({ timeout: 10_000 });
    await dialog.getByLabel("Name", { exact: true }).fill(locationName);
    await dialog
      .getByRole("combobox")
      .filter({ hasText: /select location type/i })
      .click();
    await page.getByRole("option", { name: locationTypeName }).click();
    await dialog.getByRole("button", { name: "Save", exact: true }).click();
    await expect(dialog).toBeHidden({ timeout: 10_000 });

    await page.getByPlaceholder(SEARCH_PLACEHOLDER).fill(locationName);
    const row = page.locator("button").filter({ hasText: locationName });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.click();
    await expect(
      page.getByRole("heading", { name: locationName }),
    ).toBeVisible();

    // Detail pane -> Edit -> the form swaps into the detail slot (Task 10).
    await page.getByTestId("location-edit-btn").click();
    await expect(
      page.getByRole("heading", { name: `Edit ${locationName}` }),
    ).toBeVisible();

    await page.getByLabel("PLC code").fill(plcCode);

    // Kind is a Radix Select (role=combobox); its Label has no htmlFor, so scope
    // by the field wrapper's text rather than getByLabel. The freshly-created
    // location type's random name doesn't match location-derive.ts's
    // classifyKind() substring heuristic (pick/rack/reserve/etc.), so the location
    // starts out in neither the "Pick face" nor "Reserve" filter -- setting kind
    // to PICK_FACE here is what makes it appear under that chip below.
    await page
      .locator("div.space-y-2", { hasText: "Kind" })
      .getByRole("combobox")
      .click();
    await page.getByRole("option", { name: "PICK_FACE", exact: true }).click();

    await page.getByRole("button", { name: "Save", exact: true }).click();

    // Back to the read pane; the new PLC code shows in the Constraints grid.
    await expect(
      page.getByRole("heading", { name: locationName }),
    ).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText(plcCode, { exact: true })).toBeVisible();

    // Kind persisted: the "Pick face" filter chip now includes this location
    // (search text is still active, so this also re-confirms the row by name).
    await page.getByRole("button", { name: "Pick face", exact: true }).click();
    await expect(row).toBeVisible({ timeout: 15_000 });
  });
});
