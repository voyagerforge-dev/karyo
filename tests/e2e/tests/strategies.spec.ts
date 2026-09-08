import { test, expect, keycloakLogin } from '../fixtures/auth';
import { TEST_DATA, uniqueName } from '../fixtures/test-data';

test.describe('Strategies admin', () => {
  test('manager can create and edit an order strategy', async ({ page, baseURL }) => {
    const base = baseURL || "http://localhost";
    await keycloakLogin(
      page,
      base,
      TEST_DATA.manager.username,
      TEST_DATA.manager.password,
    );

    const name = uniqueName('os').toUpperCase(); // e.g. E2E-OS-<ts>
    await page.goto(base + '/strategies');
    await expect(page.getByTestId('strategies-page')).toBeVisible();

    // Create an order strategy
    await page.getByTestId('strategy-create').click();
    await page.getByLabel('Name').fill(name);
    await page.getByTestId('order-strategy-submit').click();

    // /strategies is master-detail (P4 recompose): rows are MasterListRow
    // buttons, not DataTable cells -- select by the row's text.
    const row = page.locator('button').filter({ hasText: name });
    await expect(row).toBeVisible({ timeout: 15_000 });

    // Select the row -> the read workspace (strategy-detail.tsx) shows the
    // strategy name as its heading + a write-gated Edit button (the
    // `Edit ${name}` drawer heading is gone -- edit is now a click-through
    // from the read pane into the same form, titled "Edit <name>" on its
    // SectionCard, not an h1/h2 role-heading match on that exact string).
    await row.click();
    await expect(page.getByRole('heading', { name, exact: true })).toBeVisible();
    await page.getByTestId('strategy-edit').click();

    // Edit form: toggle "Prefer complete" off, save
    await expect(page.getByTestId('order-strategy-submit')).toBeVisible();
    await page.getByLabel('Prefer complete').click();
    await page.getByTestId('order-strategy-submit').click();

    // Row still present after the edit (persistence smoke)
    await expect(row).toBeVisible({ timeout: 15_000 });
  });
});
