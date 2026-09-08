/**
 * Admin document archive E2E.
 *
 * Archive flow: drives a delivery order to PICKED(600) via API (same
 *     recipe as orders.spec.ts's order C -- the "Delivery note" button only
 *     renders once `order.state >= PICKED`), clicks the order-detail pane's
 *     "Archive" action on the delivery-note document (data-testid
 *     "doc-delivery-note-archive-btn"), then switches to the `admin` user
 *     (only seeded principal holding `user-admin`, per webhooks.spec.ts's
 *     file-header note) to open /admin/documents and confirm the archived
 *     row + its download control are present. The entity-id filter narrows
 *     the table to just this order's row so the assertion doesn't depend on
 *     what earlier runs/specs may have archived.
 *
 * The admin-facing half needs /admin/* (AdminGuard, user-admin permission), so it
 * runs under a fresh `browser.newContext()` logged in as
 * TEST_DATA.admin -- mirrors command-palette.spec.ts's mgrCtx pattern, just
 * with the roles reversed (default page is manager for the order/stock
 * setup, a second context is admin for the /admin/* pages).
 */
import type { APIResponse, Page } from "@playwright/test";
import { test, expect, keycloakLogin } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";
import { seedAreas } from "../fixtures/scenario-helpers";

const INCOMING = 100;
const ON_STOCK = 300;

test.describe("Admin documents archive flow", () => {
  test.use({ credentials: "manager" });

  test("archiving a delivery note appears in /admin/documents with a download control", async ({
    authenticatedPage: page,
    browser,
    baseURL,
  }) => {
    test.setTimeout(180_000);

    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };
    const suffix = Date.now();

    async function expectOk(res: APIResponse, label: string) {
      expect(res.ok(), `${label} failed: ${res.status()} ${await res.text()}`).toBe(true);
    }
    async function post<T>(p: Page, path: string, data: unknown): Promise<T> {
      const res = await p.request.post(path, { headers, data });
      await expectOk(res, `POST ${path}`);
      return (await res.json()) as T;
    }
    async function get<T>(p: Page, path: string): Promise<T> {
      const res = await p.request.get(path, { headers });
      await expectOk(res, `GET ${path}`);
      return (await res.json()) as T;
    }

    type Entity = { id: number };

    // --- API setup: a small stock pool + a delivery order driven to
    // PICKED(600) -- the minimum needed for order-detail.tsx's delivery-note
    // Archive action to render (mirrors orders.spec.ts's order-C recipe).

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const areas = await seedAreas(page, headers, "admdoc");
    const unitLoad = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-ADMDOC-PAL-${suffix}`,
      unitLoadTypeId: areas.unitLoadTypeId,
      storageLocationId: areas.storeLocId,
      storageLocationName: areas.storeLocName,
    });

    const sku = `E2E-ADMDOC-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-admdoc-product-${suffix}`,
      description: "Admin documents E2E product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    const stock = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 10,
      unitLoadId: unitLoad.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stock.id}/change-state`, {
      state: ON_STOCK,
    });

    const orderNumber = `e2e-admdoc-${suffix}`;
    const order = await post<{ id: number; orderNumber: string }>(
      page,
      "/api/v1/delivery-orders",
      {
        orderNumber,
        customerName: "E2E Admin Docs Customer",
        lines: [{ itemDataId: product.id, itemDataNumber: sku, amount: 10 }],
      },
    );
    await post(page, `/api/v1/delivery-orders/${order.id}/release`, {});

    // Register row 8: POST /api/v1/pick-orders always returns a JSON array now
    // (releaseToPicking can mint more than one PickOrder per delivery order via
    // createTypeOrders) -- this order has a single line/single item type, so
    // exactly one PickOrder comes back.
    const pickOrders = await post<{
      id: number;
      picks: { id: number; plannedAmount: number }[];
    }[]>(page, "/api/v1/pick-orders", { deliveryOrderId: order.id });
    expect(pickOrders.length).toBe(1);
    const pickOrder = pickOrders[0];
    for (const p of pickOrder.picks) {
      await post(page, `/api/v1/picks/${p.id}/confirm`, {
        pickedAmount: p.plannedAmount,
      });
    }

    const orderAfter = await get<{ state: number }>(
      page,
      `/api/v1/delivery-orders/${order.id}`,
    );
    expect(orderAfter.state).toBe(600);

    // --- UI: open the order, archive its delivery note ---

    await page.goto("/orders");
    await expect(page.getByRole("heading", { name: "Orders" })).toBeVisible();

    const searchBox = page.getByPlaceholder(/search order/i);
    await searchBox.fill(orderNumber);
    const row = page.locator("button").filter({ hasText: orderNumber });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.click();
    await expect(
      page.getByRole("heading", { name: orderNumber }),
    ).toBeVisible();

    await expect(page.getByTestId("doc-delivery-note-archive-btn")).toBeVisible({
      timeout: 15_000,
    });
    await page.getByTestId("doc-delivery-note-archive-btn").click();
    // archiveDocument() toasts on success -- wait for it rather than racing
    // the archive write with the admin-side navigation below.
    await expect(page.getByText("Document archived")).toBeVisible({
      timeout: 15_000,
    });

    // --- Admin: /admin/documents shows the archived row + a download control ---
    // (fresh context -- `manager` cannot pass AdminGuard's user-admin check,
    // only `admin` can, see the file-header note.)

    const base = baseURL || "http://localhost";
    const adminCtx = await browser.newContext({ baseURL: base });
    const adminPage = await adminCtx.newPage();
    try {
      await keycloakLogin(adminPage, base, TEST_DATA.admin.username, TEST_DATA.admin.password);
      await adminPage.goto("/admin/documents");
      await expect(adminPage.getByTestId("admin-documents-page")).toBeVisible({
        timeout: 15_000,
      });

      // Narrow to this order's own archived document via the entity-id filter
      // so the assertion is independent of anything archived by prior runs.
      await adminPage.getByTestId("documents-filter-entity-id").fill(String(order.id));

      const archivedRow = adminPage.locator('[data-testid^="admin-documents-row-"]');
      await expect(archivedRow).toHaveCount(1, { timeout: 15_000 });
      await expect(
        adminPage.locator('[data-testid^="admin-documents-download-"]'),
      ).toBeVisible();
    } finally {
      await adminCtx.close();
    }
  });
});
