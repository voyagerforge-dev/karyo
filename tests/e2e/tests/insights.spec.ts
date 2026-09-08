/**
 * Insights — KPI dashboard E2E spec.
 *
 * Verifies that the Reports page (`/insights/reports`) renders the four live
 * KPI tiles returned by `GET /api/v1/insights/kpis` and survives a range switch.
 *
 * Test user: manager (TEST_DATA.manager) — holds inventory-read which is the
 * only required permission on the KPI endpoint.
 *
 * LIVE RUN DEFERRED: run against the local stack with
 *   NGINX_HTTP_PORT=8088 ./scripts/run-e2e.sh insights.spec.ts
 * (no --reset-db needed — read-only assertions, no data mutations).
 */
import { test, expect, keycloakLogin } from '../fixtures/auth';
import { TEST_DATA } from '../fixtures/test-data';

test.describe('Insights — KPI dashboard', () => {
  test('reports page shows live KPI tiles and survives a range switch', async ({ page, baseURL }) => {
    const base = baseURL || "http://localhost";

    // manager has inventory-read — broad read access covering GET /api/v1/insights/kpis
    await keycloakLogin(page, base, TEST_DATA.manager.username, TEST_DATA.manager.password);

    await page.goto(base + '/insights/reports');

    // Page shell renders
    await expect(page.getByTestId('reports-page')).toBeVisible({ timeout: 15_000 });

    // Four KPI tile labels render from the live API.
    // KpiTrendTile wraps tile.label in a div with the Tailwind `uppercase` class —
    // that is CSS text-transform only; DOM text content stays mixed-case.
    // Values may read "0.0%" / "0.0h" on a fresh tenant — labels always render.
    await expect(page.getByText('Inventory accuracy')).toBeVisible({ timeout: 15_000 });
    await expect(page.getByText('Throughput')).toBeVisible();
    await expect(page.getByText('Order cycle time')).toBeVisible();
    await expect(page.getByText('Utilization')).toBeVisible();

    // Switching to 7D triggers a refetch; tiles must still be present (no error state)
    await page.getByRole('button', { name: '7D' }).click();
    await expect(page.getByText('Inventory accuracy')).toBeVisible({ timeout: 10_000 });
  });
});
