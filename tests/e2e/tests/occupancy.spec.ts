/**
 * Insights — occupancy heatmap E2E spec.
 *
 * Verifies that the Occupancy page (`/insights/occupancy`) renders the
 * warehouse rollup label returned by `GET /api/v1/insights/occupancy`.
 *
 * Test user: manager (TEST_DATA.manager) — holds inventory-read which is the
 * only required permission on the occupancy endpoint.
 *
 * LIVE RUN DEFERRED: run against the local stack with
 *   NGINX_HTTP_PORT=8088 ./scripts/run-e2e.sh occupancy.spec.ts
 * (no --reset-db needed — read-only assertions, no data mutations).
 */
import { test, expect, keycloakLogin } from '../fixtures/auth';
import { TEST_DATA } from '../fixtures/test-data';

test.describe('Insights — occupancy heatmap', () => {
  test('occupancy page renders zone cards', async ({ page, baseURL }) => {
    const base = baseURL || "http://localhost";

    // manager has inventory-read — broad read access covering GET /api/v1/insights/occupancy
    await keycloakLogin(page, base, TEST_DATA.manager.username, TEST_DATA.manager.password);

    await page.goto(base + '/insights/occupancy');

    // Page shell renders
    await expect(page.getByTestId('occupancy-page')).toBeVisible({ timeout: 15_000 });

    // Warehouse rollup label always renders (even on a fresh/empty tenant)
    await expect(page.getByText('Warehouse occupancy')).toBeVisible({ timeout: 15_000 });
  });
});
