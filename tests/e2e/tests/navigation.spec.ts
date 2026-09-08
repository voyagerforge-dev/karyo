/**
 * Navigation E2E tests.
 *
 * Verifies sidebar links, page routing, dark mode toggle,
 * sidebar collapse/expand, and responsive layout.
 */
import { test, expect } from "../fixtures/auth";
import { TEST_DATA } from "../fixtures/test-data";

// Sidebar navigation area (shadcn sidebar). Scoping link lookups here avoids
// strict-mode collisions with same-named breadcrumb links in the header.
const SIDEBAR_NAV = '[data-sidebar="content"]';

test.describe("Navigation", () => {
  test("sidebar shows all expected nav links", async ({
    authenticatedPage,
  }) => {
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

  test("clicking each nav link routes to the correct page", async ({
    authenticatedPage,
  }) => {
    const nav = authenticatedPage.locator(SIDEBAR_NAV);

    // Click Locations
    await nav.getByRole("link", { name: "Locations", exact: true }).click();
    await expect(authenticatedPage).toHaveURL(/\/locations$/);

    // Click Items (the /products page was retired 2026-07-15, merged here)
    await nav.getByRole("link", { name: "Items", exact: true }).click();
    await expect(authenticatedPage).toHaveURL(/\/items$/);

    // Click Inventory
    await nav.getByRole("link", { name: "Inventory", exact: true }).click();
    await expect(authenticatedPage).toHaveURL(/\/inventory$/);

    // Click Users
    await nav.getByRole("link", { name: "Users", exact: true }).click();
    await expect(authenticatedPage).toHaveURL(/\/users$/);

    // Click Dashboard (back to home)
    await nav.getByRole("link", { name: "Control", exact: true }).click();
    await expect(authenticatedPage).toHaveURL(/\/$/);
  });

  test("dark mode toggle switches theme", async ({ authenticatedPage }) => {
    // Find the theme toggle button (has sr-only text "Toggle theme")
    const toggleButton = authenticatedPage.getByRole("button", {
      name: "Toggle theme",
    });
    await expect(toggleButton).toBeVisible();

    // Click to open dropdown, then click "Dark"
    await toggleButton.click();
    await authenticatedPage.getByRole("menuitem", { name: "Dark" }).click();

    // The html element should have class "dark"
    await expect(authenticatedPage.locator("html")).toHaveClass(/dark/);

    // Switch back to light
    await toggleButton.click();
    await authenticatedPage.getByRole("menuitem", { name: "Light" }).click();

    // The html element should not have "dark" anymore
    await expect(authenticatedPage.locator("html")).not.toHaveClass(/dark/);
  });

  test("sidebar can be collapsed and expanded", async ({
    authenticatedPage,
  }) => {
    // Two elements are named "Toggle Sidebar" (header trigger + sidebar rail);
    // target the header trigger via its data-slot attribute.
    const sidebarTrigger = authenticatedPage.locator(
      '[data-slot="sidebar-trigger"]',
    );
    await expect(sidebarTrigger).toBeVisible();

    // The collapse state attribute lives on the outer sidebar wrapper
    // ([data-slot="sidebar"]); the visible panel is the inner element.
    const sidebarWrapper = authenticatedPage.locator('[data-slot="sidebar"]');
    await expect(
      authenticatedPage.locator('[data-slot="sidebar-inner"]'),
    ).toBeVisible();
    await expect(sidebarWrapper).toHaveAttribute("data-state", "expanded");

    // Click to collapse
    await sidebarTrigger.click();
    await expect(sidebarWrapper).toHaveAttribute("data-state", "collapsed");

    // Click to expand
    await sidebarTrigger.click();
    await expect(sidebarWrapper).toHaveAttribute("data-state", "expanded");
  });

  // FIXED BUG (was WORKLIST Defects, filed 2026-07-21, closed 1da3d4f3
  // 2026-07-21): this assertion used to fail with a real overflow --
  // `document.documentElement.scrollWidth` (816px) > `clientWidth` (768px)
  // at this exact viewport width. AppSidebar's own
  // matchMedia('(max-width: 768px)') effect force-collapses the sidebar to
  // its icon rail right at 768px, but SidebarInset was missing `min-w-0` on
  // its flex-row item, so its default min-width:auto let descendant content
  // float it wider than its flex-computed share -- overflowing the viewport
  // by exactly the icon rail's width (48px). Root-caused and fixed by adding
  // `min-w-0` to SidebarInset (same idiom already used in
  // app-header.tsx/admin-shell.tsx); this test now passes for real.
  test("at 768px viewport width, layout remains usable", async ({
    authenticatedPage,
  }) => {
    // Set viewport to tablet width
    await authenticatedPage.setViewportSize({ width: 768, height: 1024 });

    // Wait for any responsive adjustments
    await authenticatedPage.waitForTimeout(500);

    // The page should not have horizontal scrollbar (content fits)
    const hasHorizontalScroll = await authenticatedPage.evaluate(() => {
      return (
        document.documentElement.scrollWidth >
        document.documentElement.clientWidth
      );
    });
    expect(hasHorizontalScroll).toBe(false);

    // Dashboard heading should still be visible
    await expect(
      authenticatedPage.getByRole("heading", { name: "Operations Control" }),
    ).toBeVisible();
  });
});
