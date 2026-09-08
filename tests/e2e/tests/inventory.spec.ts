/**
 * Inventory page E2E tests.
 *
 * /inventory is Control master-detail: search + status filter chips + an
 * item/location grouping toggle on the left, a read/action workspace pane
 * (Adjust/Move/Hold) on the right. There is no <table>, no paginator (the
 * page always fetches page 0 of `useStockUnits`; there is no UI to advance
 * pages), and no sortable column headers (rows are cards, not table rows).
 * This page is otherwise still read-mostly in the dashboard sense -- the
 * write actions live in the detail pane, not tested here.
 *
 * RETIRED: "pagination controls work if enough data exists" and "sorting by
 * column header changes order" -- both UI affordances (a paginator, `<table
 * thead th button>` sortable headers) no longer exist anywhere on this page
 * (confirmed by source read of inventory-page.tsx). The closest surviving
 * mechanism -- the By item / By location grouping toggle -- is covered
 * below instead.
 */
import { test, expect } from "../fixtures/auth";

const SEARCH_PLACEHOLDER = "Search item, SKU, location, lot…";

test.describe("Inventory", () => {
  test.beforeEach(async ({ authenticatedPage }) => {
    await authenticatedPage.goto("/inventory");
    await expect(authenticatedPage).toHaveURL(/\/inventory$/);
  });

  test("inventory page loads and shows stock data", async ({
    authenticatedPage,
  }) => {
    await expect(
      authenticatedPage.getByTestId("inventory-page"),
    ).toBeVisible();
    await expect(
      authenticatedPage.getByPlaceholder(SEARCH_PLACEHOLDER),
    ).toBeVisible({ timeout: 15_000 });

    // KPI strip renders totals over the aggregated item·location groups
    await expect(authenticatedPage.getByText("On-hand units")).toBeVisible();
  });

  test("the item/location grouping toggle switches without error", async ({
    authenticatedPage,
  }) => {
    await expect(
      authenticatedPage.getByPlaceholder(SEARCH_PLACEHOLDER),
    ).toBeVisible({ timeout: 15_000 });

    await authenticatedPage
      .getByRole("button", { name: "By location" })
      .click();
    await authenticatedPage.getByRole("button", { name: "By item" }).click();

    // The page (KPI strip + list) is still intact after switching axis
    await expect(
      authenticatedPage.getByTestId("inventory-page"),
    ).toBeVisible();
  });
});
