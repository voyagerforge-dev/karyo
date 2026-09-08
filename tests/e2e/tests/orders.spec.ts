/**
 * Orders E2E -- v1.2 sub-phase 2.1 (DeliveryOrder lifecycle through the UI).
 *
 * Stock setup is API-driven (workflow.spec.ts pattern): the demo sample data
 * contains no available stock, so the spec seeds its own e2e-/E2E- prefixed
 * product with 100 units ON_STOCK at a unique location. Everything else is
 * driven through the Orders UI:
 *
 *  1. create order A (1 line, 40 of 100 available) -> appears in list as
 *     "Exception" (unreserved lines always shortage>0 until released)
 *  2. release A -> order state PROCESSABLE (derived label "Allocated"), line
 *     fully reserved (40)
 *  3. create order B (500 > 60 remaining) -> release -> order stays RELEASED;
 *     the seeded DEFAULT strategy's preferComplete=true means the line's
 *     reservation is all-or-nothing, so it reserves 0 and the whole line
 *     (500) is short -> derived label "Exception" + the copilot exception strip
 *  4. cancel A -> "Canceled" chip (reservations released)
 *
 * P4 recompose note: /orders is master-detail now (no drawer, no DataTable) --
 * rows are unlabeled MasterListRow buttons and the detail pane is inline (no
 * close needed; selecting another row replaces it). order-detail-status
 * renders the v3 DERIVED status label (order-status.ts getOrderStatus: New /
 * Allocated / Picking / Ready / Shipped, forced to "Exception" by any short
 * line, or "Canceled"), not the raw backend OrderState name the drawer-era
 * spec asserted -- assertions below use the derived label where the UI text
 * matches or an API-truth check where it now cleanly doesn't (see per-step
 * comments). Step 4 cancels order A through the pane's own Cancel affordance
 * (consolidation-sprint Task 5 wired `useCancelOrder` into order-detail.tsx,
 * state-gated below PICKED(600) with an AlertDialog confirm) -- no more
 * reload-for-fresh-fetch workaround needed since the hook invalidates
 * `['orders']` on success.
 *
 * Unique e2e-ord-* order numbers per run keep the spec re-runnable; the
 * global-setup sweep removes e2e-* orders (cascade: lines + reservations)
 * and E2E-* stock from previous runs.
 *
 * Credentials: unit-load/stock creation requires a real goods owner
 * (clientId != 0) -- same fix as putaway.spec.ts / picking.spec.ts /
 * packing.spec.ts / shipping.spec.ts. The default `admin` principal is the
 * system client (id 0) and 400s on POST /api/v1/unit-loads.
 *
 * Task 9 (stock-and-orders sprint) adds a second test: the operator claim /
 * release affordances on the order detail pane (Row 10). Claim is pure
 * metadata -- it never moves DeliveryOrder.state -- so no stock/release
 * setup is needed, just an order to claim. Two things are only reachable at
 * the API level and asserted there rather than through the UI: a self-reclaim
 * (the Claim button itself unmounts the instant operatorId is set, so there
 * is no button left to click), and a non-manager's release attempt on
 * someone else's claim (a second browser context as `operator`, same
 * pattern as admin-documents.spec.ts's admin context switch).
 */
import type { APIResponse, Page } from "@playwright/test";
import { test, expect, keycloakLogin } from "../fixtures/auth";
import { captureAuthHeader } from "../fixtures/api";
import { TEST_DATA } from "../fixtures/test-data";

const INCOMING = 100;
const ON_STOCK = 300;

test.describe("Orders (UI flow)", () => {
  test.use({ credentials: "manager" });

  test("create -> release -> reserve; shortage path; cancel", async ({
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

    // --- API setup: product with 100 units ON_STOCK (no demo coupling) ---

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const locationType = await post<Entity>(page, "/api/v1/location-types", {
      name: `e2e-ord-shelf-${suffix}`,
      height: 200,
      width: 100,
      depth: 120,
      liftingCapacity: 500,
    });
    const zone = await post<Entity>(page, "/api/v1/zones", {
      name: `e2e-ord-zone-${suffix}`,
      description: "Orders E2E zone",
    });
    const area = await post<Entity>(page, "/api/v1/areas", {
      name: `e2e-ord-area-${suffix}`,
      usages: [],
    });
    const location = await post<Entity>(page, "/api/v1/locations", {
      name: `e2e-ord-loc-${suffix}`,
      scanCode: `e2e-ord-loc-${suffix}`,
      locationTypeId: locationType.id,
      areaId: area.id,
      zoneId: zone.id,
    });
    const unitLoadType = await post<Entity>(page, "/api/v1/unit-load-types", {
      name: `e2e-ord-pallet-${suffix}`,
      height: 150,
      width: 120,
      depth: 100,
      weight: 25,
      liftingCapacity: 1500,
    });
    const unitLoad = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-ORD-PAL-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: location.id,
      storageLocationName: `e2e-ord-loc-${suffix}`,
    });

    const sku = `E2E-ORD-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-ord-product-${suffix}`,
      description: "Orders E2E product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    const stock = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 100,
      unitLoadId: unitLoad.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stock.id}/change-state`, {
      state: ON_STOCK,
    });

    const orderNumberA = `e2e-ord-${suffix}-a`;
    const orderNumberB = `e2e-ord-${suffix}-b`;

    // --- UI helpers ---

    const searchBox = () => page.getByPlaceholder(/search order/i);

    async function createOrderViaUi(
      orderNumber: string,
      customer: string,
      amount: number,
    ): Promise<{ id: number; orderNumber: string }> {
      // The Dialog trigger reads "New order" (the old drawer trigger text
      // "Create Order" survives only as the Dialog's own title).
      await page.getByRole("button", { name: "New order" }).click();
      await expect(
        page.getByRole("heading", { name: "Create Order" }),
      ).toBeVisible();

      await page.getByLabel("Order #").fill(orderNumber);
      await page.getByLabel("Customer").fill(customer);

      // Product picker: searchable select backed by the products API
      await page.getByPlaceholder("Search product...").fill(sku);
      await page.getByRole("option").filter({ hasText: sku }).click();
      await page.getByLabel("Amount").fill(String(amount));

      // Capture the create response for a robust id-keyed selector below
      // (the master list has no row testid -- see openDetail).
      const createResponsePromise = page.waitForResponse(
        (res) =>
          res.request().method() === "POST" &&
          new URL(res.url()).pathname === "/api/v1/delivery-orders",
      );
      await page.getByTestId("order-form-submit").click();
      const createResponse = await createResponsePromise;
      const created = (await createResponse.json()) as {
        id: number;
        orderNumber: string;
      };

      // Dialog closes on success
      await expect(
        page.getByRole("heading", { name: "Create Order" }),
      ).toBeHidden({ timeout: 15_000 });

      return created;
    }

    async function openDetail(orderNumber: string) {
      // /orders is master-detail (P4 recompose): no drawer, no row testid --
      // rows are unlabeled MasterListRow buttons, select by their text. The
      // inline detail pane needs no "opened" container check beyond its own
      // h1 (order.orderNumber) rendering -- there's no drawer to wait on and
      // no close step; re-selecting a row just replaces the pane's content.
      await searchBox().fill(orderNumber);
      const row = page.locator("button").filter({ hasText: orderNumber });
      await expect(row).toBeVisible({ timeout: 15_000 });
      await row.click();
      await expect(
        page.getByRole("heading", { name: orderNumber }),
      ).toBeVisible();
    }

    // --- 1. Create order A (40 within the 100 available) ---

    await page.goto("/orders");
    await expect(page.getByRole("heading", { name: "Orders" })).toBeVisible();

    const orderA = await createOrderViaUi(orderNumberA, "E2E Customer A", 40);

    // Appears in the list -- but NOT as "New": getOrderStatus's exception
    // check (order-status.ts hasShortage) reads the backend's unconditional
    // `shortage = amount - reservedAmount` (DeliveryOrderResponse.kt), which
    // is > 0 for any not-yet-released line (reservedAmount is still 0) --
    // so every freshly created order derives to "Exception" until it's
    // released and its lines get reserved. Verified against the live app,
    // not assumed from the field's doc comment.
    await searchBox().fill(orderNumberA);
    const rowA = page.locator("button").filter({ hasText: orderNumberA });
    await expect(rowA).toBeVisible({ timeout: 15_000 });
    await expect(rowA.getByText("Exception", { exact: true })).toBeVisible();

    // --- 2. Release A -> PROCESSABLE (derived label "Allocated"), line fully reserved ---
    //
    // The "Exception" label is a poor sync point here: it's already showing
    // (pre-release, see step 1) before the Release click, so waiting for it
    // to reappear can resolve on the STALE pre-release fetch instead of the
    // post-release one. Synchronize on the release network response itself
    // (DeliveryOrderReleaseResponse: order + shortages) for the reservation
    // numbers, then assert the UI settles from that same source of truth.

    await openDetail(orderNumberA);
    const releaseAPromise = page.waitForResponse(
      (res) =>
        res.request().method() === "POST" &&
        new URL(res.url()).pathname === `/api/v1/delivery-orders/${orderA.id}/release`,
    );
    await page.getByRole("button", { name: "Release", exact: true }).click();
    const releaseA = (await (await releaseAPromise).json()) as {
      order: { lines: { reservedAmount: number; shortage: number }[] };
    };
    expect(releaseA.order.lines[0].reservedAmount).toBe(40);
    expect(releaseA.order.lines[0].shortage).toBe(0);

    // PROCESSABLE(300) + no shortage -> stageIndex 1 -> "Allocated".
    await expect(page.getByTestId("order-detail-status")).toHaveText(
      "Allocated",
      { timeout: 15_000 },
    );
    await expect(page.getByTestId("copilot-exception")).toBeHidden();

    // --- 3. Order B over available stock -> release -> stays RELEASED, line short ---

    const orderB = await createOrderViaUi(orderNumberB, "E2E Customer B", 500);

    await openDetail(orderNumberB);
    const releaseBPromise = page.waitForResponse(
      (res) =>
        res.request().method() === "POST" &&
        new URL(res.url()).pathname === `/api/v1/delivery-orders/${orderB.id}/release`,
    );
    await page.getByRole("button", { name: "Release", exact: true }).click();
    const releaseB = (await (await releaseBPromise).json()) as {
      order: { lines: { reservedAmount: number; shortage: number }[] };
    };
    // 100 on stock - 40 reserved by A = 60 available; preferComplete governs
    // stock-UNIT selection (whole vs split units), not an all-or-nothing per
    // line, so the reservation takes everything it can: 60 reserved, 440 short.
    expect(releaseB.order.lines[0].reservedAmount).toBe(60);
    expect(releaseB.order.lines[0].shortage).toBe(440);

    // Order stays RELEASED(100) but the short line forces the derived label
    // to "Exception" (order-status.ts: exception overrides the stage label).
    await expect(page.getByTestId("order-detail-status")).toHaveText(
      "Exception",
      { timeout: 15_000 },
    );
    // The old "shortage-callout" is now the copilot exception strip.
    const exceptionStrip = page.getByTestId("copilot-exception");
    await expect(exceptionStrip).toBeVisible();
    await expect(exceptionStrip).toContainText(sku);
    await expect(exceptionStrip).toContainText("440");
    // Line status chip sits in "Short" (order-detail.tsx statusLabel, not
    // the raw backend "PENDING" line state).
    await expect(
      page.getByTestId("order-line-row-1").getByText("Short", { exact: true }),
    ).toBeVisible();

    // --- 4. Cancel order A via the pane's Cancel affordance -> "Canceled" ---
    await openDetail(orderNumberA);
    await page.getByTestId("order-cancel-button").click();
    await page.getByRole("button", { name: "Cancel order" }).click();
    await expect(page.getByTestId("order-detail-status")).toHaveText(
      "Canceled",
      { timeout: 15_000 },
    );

    // "Canceled" chip in the list too
    await searchBox().fill(orderNumberA);
    await expect(
      page
        .locator("button")
        .filter({ hasText: orderNumberA })
        .getByText("Canceled", { exact: true }),
    ).toBeVisible({ timeout: 15_000 });

    // --- 5. (Task 8) drive a THIRD order to PICKED and assert the delivery
    // note resolves --- orders A/B/cancel above never reach PICKED(600) (A
    // stops at PROCESSABLE then gets canceled; B stays RELEASED with a
    // shortage), and order-detail.tsx's "Delivery note" button is gated
    // `order.state >= PICKED` (mirrors the backend's own DocumentNotReady(409)
    // gate) -- so a fresh order + its own small stock pool is needed to
    // exercise it. The picking UI itself (release-to-picking, pick-confirm)
    // is already covered end-to-end by picking.spec.ts; driving it here via
    // API keeps this addition fast and focused on the Orders page's own
    // affordance instead of re-testing the Tasks pick UI.

    const ulC = await post<Entity>(page, "/api/v1/unit-loads", {
      clientId: 1,
      labelId: `E2E-ORD-PAL-C-${suffix}`,
      unitLoadTypeId: unitLoadType.id,
      storageLocationId: location.id,
      storageLocationName: `e2e-ord-loc-${suffix}`,
    });
    const stockC = await post<Entity>(page, "/api/v1/stock-units", {
      itemDataId: product.id,
      itemDataNumber: sku,
      amount: 10,
      unitLoadId: ulC.id,
      state: INCOMING,
    });
    await post(page, `/api/v1/stock-units/${stockC.id}/change-state`, {
      state: ON_STOCK,
    });

    const orderNumberC = `e2e-ord-${suffix}-c`;
    const orderC = await post<{ id: number; orderNumber: string }>(
      page,
      "/api/v1/delivery-orders",
      {
        orderNumber: orderNumberC,
        customerName: "E2E Customer C",
        lines: [{ itemDataId: product.id, itemDataNumber: sku, amount: 10 }],
      },
    );
    await post(page, `/api/v1/delivery-orders/${orderC.id}/release`, {});

    // Register row 8: POST /api/v1/pick-orders always returns a JSON array now
    // (releaseToPicking can mint more than one PickOrder via createTypeOrders) --
    // order C has a single line/single item type, so exactly one comes back.
    const pickOrdersC = await post<{
      id: number;
      picks: { id: number; plannedAmount: number }[];
    }[]>(page, "/api/v1/pick-orders", { deliveryOrderId: orderC.id });
    expect(pickOrdersC.length).toBe(1);
    const pickOrderC = pickOrdersC[0];
    for (const p of pickOrderC.picks) {
      await post(page, `/api/v1/picks/${p.id}/confirm`, {
        pickedAmount: p.plannedAmount,
      });
    }

    const orderCAfter = await get<{ state: number }>(
      page,
      `/api/v1/delivery-orders/${orderC.id}`,
    );
    expect(orderCAfter.state).toBe(600);

    // UI: the Delivery note button is now offered on the order's detail pane.
    // `useDeliveryOrders` fetches one fixed page (size 50) and the search box
    // only filters that already-fetched list client-side (orders-page.tsx) --
    // order C was created via raw API, bypassing the mutation-invalidation
    // path that kept A/B's list fresh, so a full navigation is needed to
    // force a refetch that actually includes it (same reason every other
    // spec in this suite `page.goto("/orders")`s before its first search).
    await page.goto("/orders");
    await expect(page.getByRole("heading", { name: "Orders" })).toBeVisible();
    await openDetail(orderNumberC);
    await expect(page.getByTestId("doc-delivery-note-btn")).toBeVisible({
      timeout: 15_000,
    });

    // API-level assert (fast, magic-bytes-only): the button's target route
    // actually resolves to a real PDF, not just present-in-the-DOM.
    const deliveryNoteRes = await page.request.get(
      `/api/v1/delivery-orders/${orderC.id}/delivery-note.pdf`,
      { headers },
    );
    expect(
      deliveryNoteRes.ok(),
      `delivery-note.pdf failed: ${deliveryNoteRes.status()}`,
    ).toBe(true);
    const deliveryNoteBody = await deliveryNoteRes.body();
    expect(deliveryNoteBody.subarray(0, 4).toString("latin1")).toBe("%PDF");
  });

  /**
   * Task 9 (Row 10): claim is pure metadata -- DeliveryOrder.operatorId only,
   * `state` never moves -- so this needs nothing more than an order to exist;
   * no stock/release setup. Claiming, a self-reclaim conflict, a non-manager's
   * blocked release of someone else's claim, and the holder's own release are
   * all exercised in one pass so the second `operator` context is only opened
   * once.
   */
  test("operator claim and release affordances on the order detail pane", async ({
    authenticatedPage: page,
    browser,
    baseURL,
  }) => {
    test.setTimeout(120_000);

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
    type OrderWithOperator = {
      id: number;
      orderNumber: string;
      operatorId: string | null;
      state: number;
    };

    // --- API setup: a bare order (no lines needed to be pickable -- claim
    // never touches state, so a single un-priced line is enough) ---

    const itemUnits = await get<Entity[]>(page, "/api/v1/item-units");
    expect(itemUnits.length, "item units must be seeded").toBeGreaterThan(0);

    const sku = `E2E-ORD-CLAIM-${suffix}`;
    const product = await post<Entity>(page, "/api/v1/products", {
      number: sku,
      name: `e2e-ord-claim-product-${suffix}`,
      description: "Orders claim E2E product",
      weight: 0.5,
      itemUnitId: itemUnits[0].id,
    });

    const orderNumber = `e2e-ord-claim-${suffix}`;
    const order = await post<OrderWithOperator>(page, "/api/v1/delivery-orders", {
      orderNumber,
      customerName: "E2E Claim Customer",
      lines: [{ itemDataId: product.id, itemDataNumber: sku, amount: 5 }],
    });
    expect(order.operatorId).toBeNull();

    // --- UI: open the order, claim it as manager ---

    await page.goto("/orders");
    await expect(page.getByRole("heading", { name: "Orders" })).toBeVisible();
    await page.getByPlaceholder(/search order/i).fill(orderNumber);
    const row = page.locator("button").filter({ hasText: orderNumber });
    await expect(row).toBeVisible({ timeout: 15_000 });
    await row.click();
    await expect(page.getByRole("heading", { name: orderNumber })).toBeVisible();

    await expect(page.getByTestId("order-claim-button")).toBeVisible();
    await page.getByTestId("order-claim-button").click();
    await expect(page.getByTestId("order-release-operator-button")).toBeVisible({
      timeout: 15_000,
    });
    await expect(page.getByTestId("order-claim-button")).toHaveCount(0);

    const claimed = await get<OrderWithOperator>(
      page,
      `/api/v1/delivery-orders/${order.id}`,
    );
    expect(claimed.operatorId).toBe(TEST_DATA.manager.username);
    // Claim is pure metadata -- confirm it never touched order state
    // (CREATED, since this order was never released).
    expect(claimed.state).toBe(order.state);

    // --- A self-reclaim conflicts (409) -- API-level only: the Claim button
    // itself unmounts the instant operatorId is set (canClaim goes false),
    // so there is no button left in the DOM to click for this case. ---

    const reclaim = await page.request.post(
      `/api/v1/delivery-orders/${order.id}/claim`,
      { headers, data: {} },
    );
    expect(reclaim.status(), "a self-reclaim must 409").toBe(409);

    // --- A non-manager cannot release someone else's claim: second context
    // as `operator` (OPERATOR role, no MANAGER) -- same client (ACME,
    // client_id 1) as `manager`, so it sees the same order. Neither the
    // Claim nor the Release-claim button renders (isClaimedByOther is true,
    // isManager is false), and the API call itself 409s. ---

    const base = baseURL || "http://localhost";
    const opCtx = await browser.newContext({ baseURL: base });
    const opPage = await opCtx.newPage();
    try {
      await keycloakLogin(opPage, base, TEST_DATA.operator.username, TEST_DATA.operator.password);
      await opPage.goto("/orders");
      await expect(opPage.getByRole("heading", { name: "Orders" })).toBeVisible();
      await opPage.getByPlaceholder(/search order/i).fill(orderNumber);
      const opRow = opPage.locator("button").filter({ hasText: orderNumber });
      await expect(opRow).toBeVisible({ timeout: 15_000 });
      await opRow.click();
      await expect(opPage.getByRole("heading", { name: orderNumber })).toBeVisible();

      await expect(opPage.getByTestId("order-claim-button")).toHaveCount(0);
      await expect(opPage.getByTestId("order-release-operator-button")).toHaveCount(0);

      const opAuthorization = await captureAuthHeader(opPage);
      const releaseAsOperator = await opPage.request.post(
        `/api/v1/delivery-orders/${order.id}/release-operator`,
        { headers: { Authorization: opAuthorization }, data: {} },
      );
      expect(
        releaseAsOperator.status(),
        "a non-manager releasing another operator's claim must 409",
      ).toBe(409);
    } finally {
      await opCtx.close();
    }

    // --- The holder releases their own claim -- Claim button returns. ---

    await page.getByTestId("order-release-operator-button").click();
    await expect(page.getByTestId("order-claim-button")).toBeVisible({ timeout: 15_000 });
    await expect(page.getByTestId("order-release-operator-button")).toHaveCount(0);

    const released = await get<OrderWithOperator>(
      page,
      `/api/v1/delivery-orders/${order.id}`,
    );
    expect(released.operatorId).toBeNull();
  });
});
