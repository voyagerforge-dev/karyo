/**
 * Users page E2E tests (admin-only).
 *
 * Verifies the users page loads, lists at least the admin user,
 * and tests create user and view-roles flows.
 *
 * The created user uses one exact E2E-reserved identity cleaned by global setup.
 *
 * Task 4 (consolidation sprint): the page was recomposed to Control
 * master-detail (MasterList rows + a key-mounted UserDetail pane) --
 * these scenarios were ported from the old drawer/table selectors
 * without dropping coverage.
 */
import { test, expect } from "../fixtures/auth";
import {
  E2E_MANAGED_USERNAME,
  e2eProvisioningEnabled,
  isDisposableE2EUser,
  removeManagedE2EUser,
} from "../fixtures/db-cleanup";
import { TEST_DATA, uniqueName } from "../fixtures/test-data";
import type { Page } from "@playwright/test";

test("cleanup policy preserves ordinary usernames", () => {
  expect(isDisposableE2EUser({ username: "probe-inspector" })).toBe(false);
  expect(isDisposableE2EUser({ username: "verify-supervisor" })).toBe(false);
  expect(isDisposableE2EUser({ username: E2E_MANAGED_USERNAME })).toBe(true);
  expect(isDisposableE2EUser({
    username: "generated-identity",
    attributes: { karyo_e2e: ["true"] },
  })).toBe(true);
});

async function adminUserRow(page: Page) {
  await page
    .getByPlaceholder("Search username, name or email…")
    .fill(TEST_DATA.admin.username);
  const rows = page.locator('[data-testid^="user-row-"]');
  await expect(rows).toHaveCount(1, { timeout: 15_000 });
  return rows.first();
}

test.describe("Users", () => {
  test.beforeEach(async ({ authenticatedPage }) => {
    await authenticatedPage.goto("/users");
    await expect(authenticatedPage).toHaveURL(/\/users$/);
  });

  test("users page loads for admin", async ({ authenticatedPage }) => {
    await expect(authenticatedPage.getByTestId("users-page")).toBeVisible({
      timeout: 15_000,
    });
    // At least one row rendered.
    await expect(
      authenticatedPage.locator('[data-testid^="user-row-"]').first(),
    ).toBeVisible({ timeout: 15_000 });
  });

  test("user list shows at least the admin user", async ({
    authenticatedPage,
  }) => {
    await adminUserRow(authenticatedPage);
  });

  test("create a new user with username and email", async ({
    authenticatedPage: page,
  }) => {
    const provisioned = e2eProvisioningEnabled();
    const userName = provisioned ? E2E_MANAGED_USERNAME : uniqueName("user");
    const userEmail = `${userName}@e2e-test.local`;

    if (provisioned) {
      await removeManagedE2EUser();
      await page.reload();
    }

    await page.getByTestId("user-create-button").click();

    // The create form renders directly in the detail pane (no dialog).
    const pane = page.getByTestId("users-page");

    await pane.getByLabel("Username").fill(userName);
    await pane.getByLabel("Email").fill(userEmail);
    await pane.getByLabel("First Name").fill("E2E");
    await pane.getByLabel("Last Name").fill("TestUser");
    await pane.getByLabel("Goods owner").selectOption("1");
    await pane.getByLabel("Tenant authority").selectOption("owner");
    await pane
      .getByLabel("Password", { exact: true })
      .fill("E2e-test-password-1");

    await pane.getByRole("button", { name: "Save", exact: true }).click();

    // The form closes back to the empty-state pane on success.
    await expect(page.getByText("Select a user")).toBeVisible({
      timeout: 15_000,
    });

    // Verify the new row appears (narrow with the client-side search box).
    await page
      .getByPlaceholder("Search username, name or email…")
      .fill(userEmail);
    await expect(
      page.locator('[data-testid^="user-row-"]', { hasText: userEmail }),
    ).toBeVisible({ timeout: 10_000 });
  });

  test("view user detail shows role information", async ({
    authenticatedPage: page,
  }) => {
    const adminRow = await adminUserRow(page);
    await adminRow.click();

    // The detail pane opens with the user's details.
    const pane = page.getByTestId("user-detail");
    await expect(pane).toBeVisible({ timeout: 10_000 });

    // Role information is shown as pills in the pane; the admin user has ADMIN.
    await expect(pane.getByText("Roles", { exact: true })).toBeVisible();
    await expect(pane.getByText("ADMIN", { exact: true })).toBeVisible();
  });
});
