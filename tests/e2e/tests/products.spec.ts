/**
 * Items (product catalog) E2E tests.
 *
 * The /products page was retired 2026-07-15 -- the product catalog now
 * lives at /items, a Control master-detail page (search + filter chips +
 * scrollable row list on the left, a read/edit workspace pane on the
 * right; no <table>, no drawer). This suite covers the same ground the
 * retired /products drawer spec did -- list renders, an API-created
 * product appears, editing a name persists, and detail fields show in the
 * pane -- rewritten against the live master-detail selectors.
 *
 * KNOWN APP BUG (carried over from the retired /products spec): creating a
 * product through the UI form used to 422 because the Serial Number
 * Recording select's values didn't match the backend enum. `ProductForm`
 * (reused verbatim in the items edit sheet) has since been corrected to the
 * real enum values (NO_RECORD/GOODS_RECEIPT_RECORD/ALWAYS_RECORD), but
 * products are still created via the API here to keep this suite scoped to
 * the master-detail list/detail/edit surface rather than the full form.
 *
 * Each test creates its own product with a unique timestamp suffix
 * (leftovers swept by global setup), so tests are order-independent and
 * never race the list's "No items match." placeholder.
 */
import type { Page } from "@playwright/test";
import { test, expect } from "../fixtures/auth";
import { uniqueName, uniqueSku } from "../fixtures/test-data";
import { captureAuthHeader } from "../fixtures/api";

const SEARCH_PLACEHOLDER = "Search SKU, name, category…";

/** Create a product via the API (UI create is out of scope for this suite -- see header note). */
async function createProductViaApi(
  page: Page,
  auth: string,
  sku: string,
  name: string,
): Promise<void> {
  const itemUnitsResponse = await page.request.get("/api/v1/item-units", {
    headers: { Authorization: auth },
  });
  expect(itemUnitsResponse.ok()).toBeTruthy();
  const itemUnits = (await itemUnitsResponse.json()) as Array<{ id: number }>;
  expect(itemUnits.length).toBeGreaterThan(0);

  const createResponse = await page.request.post("/api/v1/products", {
    headers: { Authorization: auth },
    data: {
      number: sku,
      name,
      description: `E2E test product ${name}`,
      itemUnitId: itemUnits[0].id,
    },
  });
  expect(createResponse.ok()).toBeTruthy();
}

test.describe("Items", () => {
  test.beforeEach(async ({ authenticatedPage }) => {
    await authenticatedPage.goto("/items");
    await expect(authenticatedPage).toHaveURL(/\/items$/);
  });

  test("items page loads and shows the master-detail list", async ({
    authenticatedPage,
  }) => {
    await expect(authenticatedPage.getByTestId("items-page")).toBeVisible();
    await expect(
      authenticatedPage.getByPlaceholder(SEARCH_PLACEHOLDER),
    ).toBeVisible({ timeout: 15_000 });
  });

  test("create a new product via the API and verify it appears in the items list", async ({
    authenticatedPage: page,
  }) => {
    const auth = await captureAuthHeader(page, "/items");
    const productName = uniqueName("product");
    const productSku = uniqueSku("PRD");
    await createProductViaApi(page, auth, productSku, productName);

    // Reload so the page refetches past the SPA's react-query staleTime
    await page.goto("/items");
    await page.getByPlaceholder(SEARCH_PLACEHOLDER).fill(productSku);
    const row = page.locator("button").filter({ hasText: productSku });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await expect(row).toContainText(productName);
  });

  test("edit an item's name via the detail pane and verify the update persists", async ({
    authenticatedPage: page,
  }) => {
    const auth = await captureAuthHeader(page, "/items");
    const productName = uniqueName("product");
    const productSku = uniqueSku("PRD");
    await createProductViaApi(page, auth, productSku, productName);
    await page.goto("/items");

    await page.getByPlaceholder(SEARCH_PLACEHOLDER).fill(productSku);
    const row = page.locator("button").filter({ hasText: productSku });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.click();
    await expect(
      page.getByRole("heading", { name: productName }),
    ).toBeVisible();

    // Open the edit sheet from the detail pane's Edit button
    await page.getByRole("button", { name: "Edit", exact: true }).click();
    const sheet = page.getByRole("dialog");
    await expect(sheet).toBeVisible({ timeout: 10_000 });
    await expect(
      sheet.getByRole("heading", { name: "Edit item" }),
    ).toBeVisible();

    const updatedName = uniqueName("updated-product");
    const nameInput = sheet.locator("#product-name");
    await nameInput.clear();
    await nameInput.fill(updatedName);
    await sheet.getByRole("button", { name: "Save", exact: true }).click();
    await expect(sheet).toBeHidden({ timeout: 10_000 });

    // The updated name should now be reflected in the detail pane heading
    await expect(
      page.getByRole("heading", { name: updatedName }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test("view item details in the detail pane", async ({
    authenticatedPage: page,
  }) => {
    const auth = await captureAuthHeader(page, "/items");
    const productName = uniqueName("product");
    const productSku = uniqueSku("PRD");
    await createProductViaApi(page, auth, productSku, productName);
    await page.goto("/items");

    await page.getByPlaceholder(SEARCH_PLACEHOLDER).fill(productSku);
    const row = page.locator("button").filter({ hasText: productSku });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.click();

    // Detail pane shows the item's identity fields
    await expect(
      page.getByRole("heading", { name: productName }),
    ).toBeVisible();
    await expect(page.locator("p").filter({ hasText: productSku })).toBeVisible();
  });
});
