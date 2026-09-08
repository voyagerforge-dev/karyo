/**
 * Deterministic webhook administration E2E coverage.
 *
 * The manager owns client 1 and can create subscriptions. The admin owns
 * client 0 and reaches the page but cannot register goods-scoped webhooks.
 * Both TEST_DATA identities are externally provisioned; they are not the
 * removed production demo credentials.
 */
import { test, expect, keycloakLogin } from '../fixtures/auth';
import { TEST_DATA, uniqueName } from '../fixtures/test-data';

test.describe('Webhooks admin', () => {
  test('manager can create a webhook subscription and see the secret-once panel', async ({
    page,
    baseURL,
  }) => {
    const base = baseURL || "http://localhost";
    await keycloakLogin(page, base, TEST_DATA.manager.username, TEST_DATA.manager.password);

    const name = uniqueName('wh');

    // 1. Navigate to Admin > Integrations
    await page.goto(base + '/admin/integrations');
    await expect(page.getByTestId('admin-integrations-page')).toBeVisible({ timeout: 15_000 });

    // 2. Open the create form
    await page.getByRole('button', { name: '+ New subscription' }).click();

    // 3. Fill in subscription details
    //    Placeholders match the CreateForm inputs in admin-integrations-page.tsx.
    await page.getByPlaceholder('Acme ERP').fill(name);
    await page
      .getByPlaceholder('https://acme.internal/wms-hooks')
      .fill('https://example.com/karyo-hooks');

    // Event-types field defaults to "*" but we clear + re-fill for determinism.
    const eventsInput = page.getByPlaceholder('*');
    await eventsInput.clear();
    await eventsInput.fill('*');

    await page.getByRole('button', { name: 'Create' }).click();

    // 4. Secret-once panel
    //    JSX: "Copy the signing secret now — it won&apos;t be shown again."
    //    The apostrophe in "won't" may render as ASCII (') or typographic (')
    //    depending on the browser's HTML entity handling — regex tolerates both.
    await expect(page.getByText(/won.t be shown again/)).toBeVisible({ timeout: 10_000 });

    // Dismiss the panel
    await page.getByRole('button', { name: 'Done' }).click();

    // 5. Subscription appears in master list by its unique name
    await expect(page.getByText(name)).toBeVisible({ timeout: 10_000 });
  });

  test('admin (SYS/client 0) is refused when creating a webhook subscription', async ({
    page,
    baseURL,
  }) => {
    const base = baseURL || "http://localhost";
    await keycloakLogin(page, base, TEST_DATA.admin.username, TEST_DATA.admin.password);

    const name = uniqueName('wh-sys-refused');

    await page.goto(base + '/admin/integrations');
    await expect(page.getByTestId('admin-integrations-page')).toBeVisible({ timeout: 15_000 });

    await page.getByRole('button', { name: '+ New subscription' }).click();
    await page.getByPlaceholder('Acme ERP').fill(name);
    await page
      .getByPlaceholder('https://acme.internal/wms-hooks')
      .fill('https://example.com/karyo-hooks');
    const eventsInput = page.getByPlaceholder('*');
    await eventsInput.clear();
    await eventsInput.fill('*');

    await page.getByRole('button', { name: 'Create' }).click();

    // The real ProblemDetail detail message must surface in the toast (proves
    // the JSON body parsed — not the generic "Server returned 400" fallback).
    // .first(): CreateForm's own catch-block toast and api-client's generic
    // toast both fire on this error (pre-existing double-toast in
    // admin-integrations-page.tsx, out of this sprint's scope) — both carry
    // the same detail text, so matching the first occurrence is sufficient.
    await expect(
      page.getByText('client 0 (SYS) has no goods to subscribe to').first(),
    ).toBeVisible({
      timeout: 10_000,
    });

    // Creation was refused: no secret panel, form stays open, no list entry.
    await expect(page.getByText(/won.t be shown again/)).not.toBeVisible();
    await expect(page.getByRole('button', { name: 'Create' })).toBeVisible();
    await expect(page.getByText(name)).not.toBeVisible();
  });
});
