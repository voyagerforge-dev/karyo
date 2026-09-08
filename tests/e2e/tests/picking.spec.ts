/**
 * Picking E2E -- v1.3 sub-phase 3.2-frontend (PickOrder lifecycle through the UI).
 *
 * Like orders.spec.ts, stock + order setup is API-driven (the demo sample data
 * carries no pickable stock), then everything is driven through the UI:
 *
 *  1. seed a PACK_STAGING area + location (the pick container lands here), a
 *     STORAGE area + location, a product, and TWO stock units of that product:
 *       - unit A = 60 (created FIRST  -> FIFO-preferred)
 *       - unit B = 40 (created SECOND -> the follow-up source)
 *  2. create an order for 60 and release it (reserves 60 from A -> PROCESSABLE)
 *  3. Orders UI: open the order detail drawer -> Release to picking
 *  4. Pick Orders UI: open the pick order -> short-confirm the first pick at 50
 *     -> the 10-unit follow-up (sourced from B) appears as a `follow-up` row
 *  5. confirm the follow-up pick (default qty 10) -> PickOrder PICKED
 *  6. the delivery order itself advances to PICKED
 *
 * Unique e2e-pick-* names per run keep the spec re-runnable; the global-setup
 * sweep removes e2e-/E2E- prefixed artifacts from previous runs.
 */
import type { APIResponse, Page } from "@playwright/test";
import { test, expect } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";

const INCOMING = 100;
const ON_STOCK = 300;

test.describe("Picking (UI flow)", () => {
  // Unit-load/stock creation requires a real goods owner (clientId != 0) -- the
  // default `admin` user is the SYS tenant (client_id 0) and 400s on
  // POST /api/v1/unit-loads ("clientId must identify a goods owner"). `manager`
  // is client_id 1 (ACME) and holds fulfillment-read/-write + order-write
  // (matches the same fix already applied in putaway.spec.ts).
  test.use({ credentials: "manager" });

  test("release to picking, short confirm, follow-up appears, order PICKED", async ({
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

    // --- API setup: two stock units of one product, ON_STOCK at storage ---

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-pick-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    });

    // PACK_STAGING area + location -- the pick container is created here.
    const packArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-pick-pack-${suffix}`,
      usages: ["PACK_STAGING"],
    });
    const packLocName = `e2e-pick-packloc-${suffix}`;
    await post(page, "/api/v1/locations", {
      name: packLocName,
      scanCode: packLocName,
      locationTypeId: locationType.id,
      areaId: packArea.id,
    });

    // STORAGE area + location -- the source stock lives here.
    const storeArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-pick-store-${suffix}`,
      usages: ["STORAGE"],
    });
    const storeLocName = `e2e-pick-storeloc-${suffix}`;
    const storeLoc = await post<Entity>(page, "/api/v1/locations", {
      name: storeLocName,
      scanCode: storeLocName,
      locationTypeId: locationType.id,
      areaId: storeArea.id,
    });

    const unitLoadType = await post<Entity>(page, "/api/v1/unit-load-types", {
      name: `e2e-pick-pallet-${suffix}`,
      height: 150,
      width: 120,
      depth: 100,
      weight: 25,
      liftingCapacity: 1500,
    });

    const sku = `E2E-PICK-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-pick-product-${suffix}`,
      description: "Picking E2E product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    // Unit A = 60, created FIRST (FIFO-preferred source for the 60 reservation).
    const ulA = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-PICK-ULA-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: storeLoc.id,
      storageLocationName: storeLocName,
    });
    const stockA = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 60,
      unitLoadId: ulA.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stockA.id}/change-state`, {
      state: ON_STOCK,
    });

    // Unit B = 40, created SECOND (the follow-up source after the short pick).
    const ulB = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-PICK-ULB-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: storeLoc.id,
      storageLocationName: storeLocName,
    });
    const stockB = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 40,
      unitLoadId: ulB.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stockB.id}/change-state`, {
      state: ON_STOCK,
    });

    // Order for 60 -> release reserves 60 from A -> PROCESSABLE.
    const orderNumber = `e2e-pick-${suffix}`;
    const order = await post<{ id: number; orderNumber: string }>(
      page,
      "/api/v1/delivery-orders",
      {
        orderNumber,
        customerName: "E2E Pick",
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
    // is known -- used below for a robust work-row selector instead of
    // guessing at row text (the listener must attach before the click).
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
    // "Release to picking" action itself unmounts (no drawer to hide) --
    // wait for that instead so the pick order + list invalidation land
    // before navigating.
    await expect(page.getByTestId("release-to-picking-btn")).toBeHidden({
      timeout: 15_000,
    });

    // --- UI: Tasks (PICK) -> select the pick work item -> short confirm at 50 ---
    // (P3: /pick-orders is retired -- pick work now lives in the unified
    // /tasks inbox; `?type=PICK` deep-links the type filter. Rows are
    // `work-row-{ref}` cards, ref = "PICK:{pickOrderId}".)

    await page.goto("/tasks?type=PICK");
    await expect(page.getByTestId("tasks-page")).toBeVisible();

    // Narrow the inbox with the MasterList search (also sidesteps the
    // list's row cap), then select the pick order by its deterministic ref.
    await page.getByPlaceholder("Search work…").fill(order.orderNumber);
    const pickRow = page.getByTestId(`work-row-PICK:${pickOrder.id}`);
    await expect(pickRow).toBeVisible({ timeout: 15_000 });
    await pickRow.click();
    await expect(page.getByTestId("picks-table")).toBeVisible();

    await page.locator('[data-testid^="pick-confirm-btn-"]').first().click();
    await expect(page.getByTestId("pick-confirm-form")).toBeVisible();
    await page.getByLabel(/picked qty/i).fill("50");
    await page.getByRole("button", { name: /confirm pick/i }).click();

    // Short pick spawns a 10-unit follow-up sourced from B.
    await expect(page.getByTestId("picks-table")).toContainText("follow-up", {
      timeout: 15_000,
    });

    // --- Confirm the follow-up pick (default qty 10) ---

    await page.locator('[data-testid^="pick-confirm-btn-"]').first().click();
    await expect(page.getByTestId("pick-confirm-form")).toBeVisible();
    await page.getByRole("button", { name: /confirm pick/i }).click();

    // --- PickOrder PICKED; the delivery order advances to PICKED ---
    // Confirming the FINAL pick moves the PickOrder out of the claimable/
    // claimed pool (PickOrderRepository.findClaimable/findClaimedBy filter on
    // RELEASED/STARTED), and useConfirmPick invalidates ['work'] alongside
    // ['pick-orders']/['orders'] -- so once the refetch lands, the row leaves
    // the /tasks inbox and the detail pane unmounts back to its empty state
    // (same "leaves the pool" pattern as putaway.spec.ts's Complete step).
    // Assert that instead of a transient "PICKED" text on a pane that's about
    // to vanish -- the pick-orders API is the source of truth for the
    // terminal state.
    await expect(pickRow).toBeHidden({ timeout: 15_000 });
    await expect(page.getByTestId("picks-table")).not.toBeVisible();

    const pickOrderAfter = await get<{ state: number }>(
      page,
      `/api/v1/pick-orders/${pickOrder.id}`,
    );
    expect(pickOrderAfter.state).toBe(600);

    await page.goto("/orders");
    await page.getByPlaceholder(/search order/i).fill(order.orderNumber);
    const orderRowAfter = page.locator("button").filter({ hasText: order.orderNumber });
    await expect(orderRowAfter).toBeVisible({ timeout: 15_000 });
    await orderRowAfter.click();
    await expect(page.getByRole("heading", { name: order.orderNumber })).toBeVisible();
    // The v3 status pill shows the friendly stage label ("Picking" covers both
    // in-progress and fully-picked, per order-status.ts stageIndexForState),
    // not the raw backend state name -- confirm that, then nail the precise
    // backend truth (ORDER_STATE.PICKED = 600) via the API.
    await expect(page.getByTestId("order-detail-status")).toHaveText("Picking", {
      timeout: 15_000,
    });
    const orderAfter = await get<{ state: number }>(
      page,
      `/api/v1/delivery-orders/${order.id}`,
    );
    expect(orderAfter.state).toBe(600);

    // --- (Task 8) pick-ticket.pdf resolves for this pick order ---
    // No availability gate on this route (unlike the shipment docs / delivery
    // note) -- it's useful in every pre-terminal pick-order state, so a
    // terminal PICKED pick order is as good a point as any to assert it. Fast
    // API-level check (magic-bytes-only, no PDF parsing) via `page.request`,
    // matching the idiom shipping.spec.ts uses for its own new doc asserts.
    const pickTicketRes = await page.request.get(
      `/api/v1/pick-orders/${pickOrder.id}/pick-ticket.pdf`,
      { headers },
    );
    expect(
      pickTicketRes.ok(),
      `pick-ticket.pdf failed: ${pickTicketRes.status()}`,
    ).toBe(true);
    const pickTicketBody = await pickTicketRes.body();
    expect(pickTicketBody.subarray(0, 4).toString("latin1")).toBe("%PDF");
  });

  // Task 6 / row 14: cancel lifecycle through the UI. A fresh RELEASED pick
  // order (no picks confirmed yet) cancels to CANCELED (adjudication 2: zero
  // PICKED picks -> CANCELED, not FINISHED) and must leave the /tasks pool
  // (PickOrderRepository.findClaimable/findClaimedBy no longer match it) --
  // same "leaves the pool" assertion idiom as the PICKED case above.
  test("cancel a pick order releases its stock and removes the work item", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(180_000);

    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };
    const suffix = `${Date.now()}-cancel`;

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

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-pick-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    });
    const storeArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-pick-store-${suffix}`,
      usages: ["STORAGE"],
    });
    const storeLocName = `e2e-pick-storeloc-${suffix}`;
    const storeLoc = await post<Entity>(page, "/api/v1/locations", {
      name: storeLocName,
      scanCode: storeLocName,
      locationTypeId: locationType.id,
      areaId: storeArea.id,
    });
    const unitLoadType = await post<Entity>(page, "/api/v1/unit-load-types", {
      name: `e2e-pick-pallet-${suffix}`,
      height: 150,
      width: 120,
      depth: 100,
      weight: 25,
      liftingCapacity: 1500,
    });

    const sku = `E2E-PICK-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-pick-product-${suffix}`,
      description: "Picking E2E cancel product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    const ul = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-PICK-CANCEL-UL-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: storeLoc.id,
      storageLocationName: storeLocName,
    });
    const stock = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 30,
      unitLoadId: ul.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stock.id}/change-state`, {
      state: ON_STOCK,
    });

    // Keep short: PickOrderService derives "PO-<orderNumber>-XXXXXX" into a VARCHAR(40) column,
    // so the order number must stay <= 30 chars ("suffix" already carries the -cancel marker).
    const orderNumber = `e2e-pkc-${Date.now()}`;
    const order = await post<{ id: number; orderNumber: string }>(
      page,
      "/api/v1/delivery-orders",
      {
        orderNumber,
        customerName: "E2E Pick Cancel",
        lines: [{ itemDataId: product.id, itemDataNumber: sku, amount: 20 }],
      },
    );
    await post(page, `/api/v1/delivery-orders/${order.id}/release`, {});

    // Register row 8: the endpoint always returns a JSON array now (single
    // line/single item type here -> exactly one PickOrder comes back).
    const cancelPickOrders = await post<{ id: number; pickOrderNumber: string }[]>(
      page,
      "/api/v1/pick-orders",
      { deliveryOrderId: order.id },
    );
    expect(cancelPickOrders.length).toBe(1);
    const pickOrder = cancelPickOrders[0];

    await page.goto("/tasks?type=PICK");
    await expect(page.getByTestId("tasks-page")).toBeVisible();
    await page.getByPlaceholder("Search work…").fill(order.orderNumber);
    const pickRow = page.getByTestId(`work-row-PICK:${pickOrder.id}`);
    await expect(pickRow).toBeVisible({ timeout: 15_000 });
    await pickRow.click();
    await expect(page.getByTestId("picks-table")).toBeVisible();

    await page.getByTestId("pick-order-cancel-btn").click();
    await page.getByTestId("pick-order-cancel-confirm-btn").click();

    // Canceling removes the order from findClaimable/findClaimedBy -> the row
    // leaves the /tasks pool exactly like a confirmed-final pick does.
    await expect(pickRow).toBeHidden({ timeout: 15_000 });
    await expect(page.getByTestId("picks-table")).not.toBeVisible();

    const pickOrderAfter = await get<{ state: number }>(
      page,
      `/api/v1/pick-orders/${pickOrder.id}`,
    );
    // Zero picks were PICKED -> CANCELED (adjudication 2), not FINISHED.
    expect(pickOrderAfter.state).toBe(800);
  });

  // Task 6 / row 20: stock-clearance (extinguish) picks. API-seeded (no order
  // driving it -- extinguish orders have no backing DeliveryOrder), the
  // resulting EXT- pick order is asserted through the same /tasks?type=PICK
  // inbox the rest of this spec drives (pick orders have no standalone list
  // page today -- the unified work inbox is the only pick-order surface).
  test("extinguish creates an EXT- pick order visible in the tasks inbox", async ({
    authenticatedPage: page,
  }) => {
    test.setTimeout(120_000);

    const authorization = await captureAuthHeader(page);
    const headers = { Authorization: authorization };
    const suffix = `${Date.now()}-ext`;

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

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-pick-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    });
    const storeArea = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-pick-store-${suffix}`,
      usages: ["STORAGE"],
    });
    const storeLocName = `e2e-pick-storeloc-${suffix}`;
    const storeLoc = await post<Entity>(page, "/api/v1/locations", {
      name: storeLocName,
      scanCode: storeLocName,
      locationTypeId: locationType.id,
      areaId: storeArea.id,
    });
    const unitLoadType = await post<Entity>(page, "/api/v1/unit-load-types", {
      name: `e2e-pick-pallet-${suffix}`,
      height: 150,
      width: 120,
      depth: 100,
      weight: 25,
      liftingCapacity: 1500,
    });

    const sku = `E2E-PICK-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-pick-product-${suffix}`,
      description: "Picking E2E extinguish product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    const ul = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-PICK-EXT-UL-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: storeLoc.id,
      storageLocationName: storeLocName,
    });
    const stock = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 15,
      unitLoadId: ul.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stock.id}/change-state`, {
      state: ON_STOCK,
    });

    const extOrder = await post<{ id: number; pickOrderNumber: string }>(
      page,
      "/api/v1/pick-orders/extinguish",
      { stockUnitIds: [stock.id] },
    );
    expect(extOrder.pickOrderNumber).toMatch(/^EXT-/);

    await page.goto("/tasks?type=PICK");
    await expect(page.getByTestId("tasks-page")).toBeVisible();
    await page.getByPlaceholder("Search work…").fill(extOrder.pickOrderNumber);
    const extRow = page.getByTestId(`work-row-PICK:${extOrder.id}`);
    await expect(extRow).toBeVisible({ timeout: 15_000 });
    await extRow.click();
    await expect(page.getByTestId("picks-table")).toBeVisible();
    // No backing delivery order -- the pane renders the honest "—" fallback
    // (Task 6) instead of a fabricated order number.
    await expect(page.getByText("Order —")).toBeVisible();
  });
});
