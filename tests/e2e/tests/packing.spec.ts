/**
 * Packing E2E -- v1.3 sub-phase 3.3-frontend (Shipment lifecycle through the UI).
 *
 * Continues exactly where picking.spec.ts leaves off: drive an order all the way
 * to PICKED via the same API setup + UI release/confirm steps, then pack it.
 *
 * Setup (mirrors picking.spec): seed a PACK_STAGING area + location, a STORAGE
 * area + location, a product, and ONE stock unit of 60 (no follow-up needed --
 * packing only cares that the order reaches PICKED). Then:
 *
 *  1. create an order for 60 and release it (reserves 60 -> PROCESSABLE)
 *  2. Orders UI: open the order detail drawer -> Release to picking
 *  3. Pick Orders UI: open the pick order -> confirm the pick at full qty (60)
 *     -> PickOrder PICKED; the delivery order advances to PICKED
 *  4. Packing UI: the PICKED order shows under "Ready to pack"
 *  5. click Pack -> the pack drawer opens (a Shipment is created, PACKING(640))
 *  6. fill weight 2.5 + Confirm pack -> the shipment flips to PACKED(650) and the
 *     read-only shipping-units summary appears
 *  7. the order leaves "Ready to pack"
 *
 * Unique e2e-pack-* names per run keep the spec re-runnable; the global-setup
 * sweep removes e2e-/E2E- prefixed artifacts from previous runs.
 */
import type { APIResponse, Page } from "@playwright/test";
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";

const INCOMING = 100;
const ON_STOCK = 300;

test.describe("Packing (UI flow)", () => {
  // Unit-load/stock creation requires a real goods owner (clientId != 0) --
  // see the same fix in putaway.spec.ts / picking.spec.ts.
  test.use({ credentials: "manager" });

  test("pick an order, then pack it into a shipping unit", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(240_000);

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

    // --- API setup: one stock unit of one product, ON_STOCK at storage ---

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-pack-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    });

    // PACK_STAGING area + location -- the pick container is created here.
    const packArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-pack-pack-${suffix}`,
      usages: ["PACK_STAGING"],
    });
    const packLocName = `e2e-pack-packloc-${suffix}`;
    await post(page, "/api/v1/locations", {
      name: packLocName,
      scanCode: packLocName,
      locationTypeId: locationType.id,
      areaId: packArea.id,
    });

    // STORAGE area + location -- the source stock lives here.
    const storeArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-pack-store-${suffix}`,
      usages: ["STORAGE"],
    });
    const storeLocName = `e2e-pack-storeloc-${suffix}`;
    const storeLoc = await post<Entity>(page, "/api/v1/locations", {
      name: storeLocName,
      scanCode: storeLocName,
      locationTypeId: locationType.id,
      areaId: storeArea.id,
    });

    const unitLoadType = await post<Entity>(page, "/api/v1/unit-load-types", {
      name: `e2e-pack-pallet-${suffix}`,
      height: 150,
      width: 120,
      depth: 100,
      weight: 25,
      liftingCapacity: 1500,
    });

    const sku = `E2E-PACK-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-pack-product-${suffix}`,
      description: "Packing E2E product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    // Single source unit = 60 (full-qty pick -> order reaches PICKED cleanly).
    const ul = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-PACK-UL-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: storeLoc.id,
      storageLocationName: storeLocName,
    });
    const stock = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 60,
      unitLoadId: ul.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stock.id}/change-state`, {
      state: ON_STOCK,
    });

    // Order for 60 -> release reserves 60 -> PROCESSABLE.
    const orderNumber = `e2e-pack-${suffix}`;
    const order = await post<{ id: number; orderNumber: string }>(
      page,
      "/api/v1/delivery-orders",
      {
        orderNumber,
        customerName: "E2E Pack",
        lines: [{ itemDataId: product.id, itemDataNumber: sku, amount: 60 }],
      },
    );
    await post(page, `/api/v1/delivery-orders/${order.id}/release`, {});

    // --- UI: Orders -> open order detail -> Release to picking ---

    await page.goto("/orders");
    await expect(page.getByRole("heading", { name: "Orders" })).toBeVisible();

    // Orders is also a master-detail inbox now (no drawer, no row testid) --
    // rows are unlabeled MasterListRow buttons, so select by their text.
    await page.getByPlaceholder(/search order/i).fill(order.orderNumber);
    const orderRow = page.locator("button").filter({ hasText: order.orderNumber });
    await expect(orderRow).toBeVisible({ timeout: 15_000 });
    await orderRow.click();
    await expect(page.getByRole("heading", { name: order.orderNumber })).toBeVisible();

    // Capture the POST /api/v1/pick-orders response so the new pick order's id
    // is known -- used below for a robust work-row selector (the listener
    // must attach before the click).
    const releaseResponsePromise = page.waitForResponse(
      (res) =>
        res.request().method() === "POST" &&
        new URL(res.url()).pathname === "/api/v1/pick-orders",
    );
    await page.getByTestId("release-to-picking-btn").click();
    const releaseResponse = await releaseResponsePromise;
    // Register row 8: the endpoint always returns a JSON array now
    // (releaseToPicking can mint more than one PickOrder via createTypeOrders) --
    // this order is a single line/single item type, so exactly one comes back.
    const releasedPickOrders = (await releaseResponse.json()) as {
      id: number;
      pickOrderNumber: string;
    }[];
    expect(releasedPickOrders.length).toBe(1);
    const pickOrder = releasedPickOrders[0];
    // Release-to-picking advances the order past PROCESSABLE, so the
    // "Release to picking" action itself unmounts (no drawer to hide).
    await expect(page.getByTestId("release-to-picking-btn")).toBeHidden({
      timeout: 15_000,
    });

    // --- UI: Tasks (PICK) -> select the pick work item -> confirm at full qty (60) ---
    // (P3: /pick-orders is retired -- pick work now lives in the unified
    // /tasks inbox; `?type=PICK` deep-links the type filter. Rows are
    // `work-row-{ref}` cards, ref = "PICK:{pickOrderId}".)

    await page.goto("/tasks?type=PICK");
    await expect(page.getByTestId("tasks-page")).toBeVisible();

    await page.getByPlaceholder("Search work…").fill(order.orderNumber);
    const pickRow = page.getByTestId(`work-row-PICK:${pickOrder.id}`);
    await expect(pickRow).toBeVisible({ timeout: 15_000 });
    await pickRow.click();
    await expect(page.getByTestId("picks-table")).toBeVisible();

    await page.locator('[data-testid^="pick-confirm-btn-"]').first().click();
    await expect(page.getByTestId("pick-confirm-form")).toBeVisible();
    await page.getByRole("button", { name: /confirm pick/i }).click();

    // PickOrder PICKED; the delivery order advances to PICKED. Confirming
    // the FINAL pick moves the PickOrder out of the claimable/claimed pool
    // (PickOrderRepository.findClaimable/findClaimedBy filter on
    // RELEASED/STARTED), and useConfirmPick invalidates ['work'] alongside
    // ['pick-orders']/['orders'] -- so the row leaves the /tasks inbox and
    // the detail pane unmounts (same "leaves the pool" pattern as
    // putaway.spec.ts). Assert that, then the pick-orders API for the
    // terminal state (not a transient pane state text).
    await expect(pickRow).toBeHidden({ timeout: 15_000 });
    await expect(page.getByTestId("picks-table")).not.toBeVisible();

    const pickOrderAfter = await get<{ state: number }>(
      page,
      `/api/v1/pick-orders/${pickOrder.id}`,
    );
    expect(pickOrderAfter.state).toBe(600);

    // --- UI: Packing -> the PICKED order is ready to pack ---
    // /packing is master-detail (P4 recompose): rows are MasterListRow
    // buttons under the "Ready to pack" chip (the default filter), keyed
    // pack-btn-{pickOrderId} -- the same id captured from the release
    // response above (ReadyRow renders one row per PICKED PickOrderResponse).

    await page.goto("/packing");
    await expect(page.getByTestId("packing-page")).toBeVisible();

    const packBtn = page.getByTestId(`pack-btn-${pickOrder.id}`);
    await expect(packBtn).toBeVisible({ timeout: 15_000 });

    // --- Pack: select the row (opens a PACKING shipment in the detail pane) -> weigh -> confirm ---

    await packBtn.click();
    await expect(page.getByTestId("pack-contents")).toBeVisible();
    await expect(page.getByTestId("pack-form")).toBeVisible({ timeout: 15_000 });

    await page.locator("#pack-weight").fill("2.5");
    await page.getByTestId("pack-confirm-btn").click();

    // Shipment flips PACKING(640) -> PACKED(650): the read-only units summary appears.
    await expect(page.getByTestId("packed-summary")).toBeVisible({
      timeout: 15_000,
    });
    await expect(
      page.locator('[data-testid^="shipping-unit-"]').first(),
    ).toBeVisible();

    // --- The packed order leaves "Ready to pack" ---

    await page.goto("/packing");
    await expect(page.getByTestId("packing-page")).toBeVisible();
    await expect(page.getByTestId(`pack-btn-${pickOrder.id}`)).toHaveCount(0, {
      timeout: 15_000,
    });
  });
});
